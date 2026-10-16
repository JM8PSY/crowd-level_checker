-- ノードが部屋を選べるようにする（2026-10-17）
--
-- - 部屋は 3F休憩室（rest-3f）と 2F休憩室（rest-2f）。ノード（スマホ）のアプリで切り替える
-- - ノードは観測値に room_id を添えて送る。サーバはその部屋の観測として保存し、
--   ノードの今の部屋（nodes.room_id）も最新の観測の部屋に切り替える。room_id がなければ今の部屋
-- - rooms に floor（階）・sort_order（表示順）・active（使っているか）を足す。
--   使わない部屋は get_rooms に出さず、観測も受け付けない。過去の推移は get_room_history で読める
-- - get_rooms に、部屋を1つだけ返す引数 room_id を足す

alter table public.rooms
  add column floor text,
  add column sort_order int not null default 0,
  add column active boolean not null default true;

insert into public.rooms (id, name, floor, sort_order) values
  ('rest-3f', '3F休憩室', '3F', 1),
  ('rest-2f', '2F休憩室', '2F', 2)
on conflict (id) do update set name = excluded.name, floor = excluded.floor, sort_order = excluded.sort_order, active = true;

-- 試験用だった教室は使わない（データは残す）。そこにいたノードは 3F休憩室へ
update public.rooms set active = false where id = 'room-1';
update public.nodes set room_id = 'rest-3f' where room_id = 'room-1';

create or replace function public.submit_observations(payload jsonb) returns jsonb
language plpgsql volatile security definer
set search_path = ''
as $$
declare
  v_token text := private.request_header('x-node-token');
  v_node  public.nodes;
  v_items jsonb;
  v_item  jsonb;
  v_room  text;
  v_estimator jsonb;
  v_observed timestamptz;
  v_latest jsonb;
  v_latest_at timestamptz;
  v_latest_room text;
  v_created int := 0;
  v_duplicate int := 0;
  v_partial int := 0;
  v_rows int;
begin
  if v_token is null or v_token = '' then
    raise exception 'ヘッダ x-node-token が必要です' using errcode = '28000';
  end if;
  select * into v_node from public.nodes n where n.token_hash = private.hash_token(v_token);
  if not found then
    raise exception 'ノードトークンが無効です' using errcode = '28000';
  end if;

  v_items := case jsonb_typeof(payload) when 'array' then payload when 'object' then jsonb_build_array(payload) end;
  if v_items is null or jsonb_array_length(v_items) = 0 then
    raise exception 'payload は観測値のオブジェクトかその配列' using errcode = '22023';
  end if;
  if jsonb_array_length(v_items) > 500 then
    raise exception '1回に送れるのは500件まで' using errcode = '22023';
  end if;

  for v_item in select value from jsonb_array_elements(v_items) loop
    if v_item ->> 'node_id' is distinct from v_node.id then
      raise exception 'トークンはノード % のものです（node_id=%）', v_node.id, v_item ->> 'node_id' using errcode = '28000';
    end if;
    if jsonb_typeof(v_item -> 'counts_by_rssi') <> 'object' or jsonb_typeof(v_item -> 'counts_by_vendor') <> 'object' then
      raise exception 'counts_by_rssi と counts_by_vendor はオブジェクト' using errcode = '22023';
    end if;
    begin
      v_observed := (v_item ->> 'observed_at')::timestamptz;
    exception when others then
      raise exception 'observed_at が読めません: %', v_item ->> 'observed_at' using errcode = '22023';
    end;
    if v_observed is null or coalesce((v_item ->> 'window_sec')::int, 0) <= 0 then
      raise exception 'observed_at と window_sec（正の整数）が必要です' using errcode = '22023';
    end if;

    -- 部屋（任意）。省くとノードの今の部屋。使っている部屋だけ受け付ける
    v_room := coalesce(v_item ->> 'room_id', v_node.room_id);
    if not exists (select 1 from public.rooms r where r.id = v_room and r.active) then
      raise exception '部屋 % はありません（使える部屋は get_rooms で確認）', v_room using errcode = '22023';
    end if;

    -- 推定の式（任意）。保存するのは feature / coef / intercept だけ
    v_estimator := nullif(v_item -> 'estimator', 'null'::jsonb);
    if v_estimator is not null then
      if jsonb_typeof(v_estimator) <> 'object'
         or coalesce(v_estimator ->> 'feature', '') not in ('-60', '-70', '-80', '-90', 'all')
         or jsonb_typeof(v_estimator -> 'coef') is distinct from 'number'
         or (v_estimator ? 'intercept' and jsonb_typeof(v_estimator -> 'intercept') <> 'number') then
        raise exception 'estimator は {"feature": "-60|-70|-80|-90|all", "coef": 数値, "intercept": 数値}' using errcode = '22023';
      end if;
      v_estimator := jsonb_build_object(
        'feature', v_estimator ->> 'feature',
        'coef', v_estimator -> 'coef',
        'intercept', coalesce(v_estimator -> 'intercept', '0'::jsonb));
    end if;

    if v_latest_at is null or v_observed > v_latest_at then
      v_latest_at := v_observed;
      v_latest := v_item;
      v_latest_room := v_room;
    end if;

    -- 部分的な窓は保存しない（エラーにはしないので再送されない）
    if (v_item ->> 'partial')::boolean is true then
      v_partial := v_partial + 1;
      continue;
    end if;

    -- devices など余分なキーは保存しない
    insert into public.observations (
      node_id, room_id, observed_at, window_sec, counts_by_rssi, counts_by_vendor, rssi_stats,
      adv_count, battery, charging, app_version, estimator
    ) values (
      v_node.id, v_room, v_observed, (v_item ->> 'window_sec')::int,
      v_item -> 'counts_by_rssi', v_item -> 'counts_by_vendor',
      nullif(v_item -> 'rssi_stats', 'null'::jsonb),
      (v_item ->> 'adv_count')::int, (v_item ->> 'battery')::int, (v_item ->> 'charging')::boolean,
      v_item ->> 'app_version', v_estimator
    )
    on conflict (node_id, observed_at) do nothing;
    get diagnostics v_rows = row_count;
    if v_rows = 1 then v_created := v_created + 1; else v_duplicate := v_duplicate + 1; end if;
  end loop;

  -- 最終受信はノードの生死判定に使うので、重複や部分的な窓だけでも更新する。
  -- ノードの今の部屋は、この送信で最も新しい観測の部屋（古い観測の再送では戻さない）
  update public.nodes n set
    last_seen_at = now(),
    battery = (v_latest ->> 'battery')::int,
    charging = (v_latest ->> 'charging')::boolean,
    app_version = v_latest ->> 'app_version',
    room_id = case
      when not exists (select 1 from public.observations o where o.node_id = n.id and o.observed_at > v_latest_at)
      then v_latest_room else n.room_id end
  where n.id = v_node.id;

  return jsonb_build_object('created', v_created, 'duplicate', v_duplicate, 'ignored_partial', v_partial);
end;
$$;

-- 引数を足すので作り直す（権限も付け直す）
drop function public.get_rooms();

-- 使っている部屋の最新の混雑度とノードの状態。room_id を指定するとその部屋だけ
-- 部屋の推定人数は、その部屋にいる online のノードの最新の観測を、それぞれの式で推定した値の平均。
-- ノードが別の部屋へ移ったあとの古い観測は使わない（最新の観測の部屋が今の部屋と同じときだけ使う）
create function public.get_rooms(room_id text default null) returns jsonb
language sql stable security definer
set search_path = ''
as $$
  with node_latest as (
    select n.*, n.last_seen_at > now() - interval '3 minutes' as online,
      o.observed_at as last_observed_at, o.room_id as observed_room_id, o.counts_by_rssi, o.estimator
    from public.nodes n
    left join lateral (
      select o.observed_at, o.room_id, o.counts_by_rssi, o.estimator from public.observations o
      where o.node_id = n.id order by o.observed_at desc limit 1
    ) o on true
  ),
  per_room as (
    select r.id, r.name, r.floor, r.sort_order, p.*,
      (select avg(private.estimate_obs(nl.counts_by_rssi, nl.estimator, p.feature, p.coef, p.intercept))
         from node_latest nl where nl.room_id = r.id and nl.observed_room_id = r.id and nl.online) as people,
      (select max(o.observed_at) from public.observations o where o.room_id = r.id) as updated_at
    from public.rooms r
    cross join lateral private.params_for(r.id) p
    where r.active and (get_rooms.room_id is null or r.id = get_rooms.room_id)
  )
  select jsonb_build_object(
    'now', now(),
    'rooms', coalesce(jsonb_agg(jsonb_build_object(
      'id', pr.id,
      'name', pr.name,
      'floor', pr.floor,
      'level', private.level_of(pr.people, pr.threshold_moderate, pr.threshold_crowded),
      'estimated_people', round(pr.people::numeric, 1),
      'updated_at', pr.updated_at,
      'calibration', jsonb_build_object(
        'feature', pr.feature, 'coef', pr.coef, 'intercept', pr.intercept,
        'threshold_moderate', pr.threshold_moderate, 'threshold_crowded', pr.threshold_crowded,
        'calibrated', pr.calibrated),
      'nodes', (
        select coalesce(jsonb_agg(jsonb_build_object(
          'id', nl.id,
          'status', case when nl.online then 'online' else 'offline' end,
          'last_seen_at', nl.last_seen_at,
          'last_observed_at', nl.last_observed_at,
          'estimator', nl.estimator,
          'battery', nl.battery, 'charging', nl.charging, 'app_version', nl.app_version
        ) order by nl.id), '[]'::jsonb)
        from node_latest nl where nl.room_id = pr.id)
    ) order by pr.sort_order, pr.id), '[]'::jsonb)
  )
  from per_room pr
$$;

revoke execute on function public.get_rooms(text) from public;
grant execute on function public.get_rooms(text) to anon, authenticated;

-- 管理用：部屋の追加・変更（SQL Editor から）
--   select private.set_room('rest-1f', '1F休憩室', '1F', 0);
--   select private.set_room('room-1', '教室1（試験）', null, 99, false);   -- 使わない部屋にする
create function private.set_room(
  p_id text, p_name text, p_floor text default null, p_sort_order int default 0, p_active boolean default true
) returns void
language sql
set search_path = ''
as $$
  insert into public.rooms (id, name, floor, sort_order, active)
  values (p_id, p_name, p_floor, p_sort_order, p_active)
  on conflict (id) do update set
    name = excluded.name, floor = excluded.floor, sort_order = excluded.sort_order, active = excluded.active
$$;
revoke execute on function private.set_room(text, text, text, int, boolean) from public;
