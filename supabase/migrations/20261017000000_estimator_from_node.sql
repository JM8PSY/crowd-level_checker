-- 推定人数の式をノードから受け取る（2026-10-17）
--
-- ノードは観測値に推定の式 {"feature": "-60", "coef": 0.5, "intercept": 0} を添えて送る。
-- サーバはその観測を、添えられた式で推定人数にする。式は観測ごとに保存するので、
-- ノードの式をあとで変えても過去の推移はそのときの式のまま残る。
-- 式のない観測（この変更より前のもの、式を送らないクライアント）は、これまでどおり
-- calibrations の最新の行（なければ既定値 -80 × 1 + 0）を使う。
-- 段階の閾値（やや混雑・混雑）は、これまでどおり calibrations（なければ 15 / 30）から読む。

alter table public.observations add column estimator jsonb;

-- 1件の観測の推定人数。p_estimator があればその式、なければ代わりの式を使う
create function private.estimate_obs(
  p_counts jsonb, p_estimator jsonb,
  p_feature text, p_coef double precision, p_intercept double precision
) returns double precision
language sql immutable
set search_path = ''
as $$
  select case
    when p_estimator is null then
      private.estimate((p_counts ->> p_feature)::double precision, p_coef, p_intercept)
    else
      private.estimate(
        (p_counts ->> (p_estimator ->> 'feature'))::double precision,
        (p_estimator ->> 'coef')::double precision,
        coalesce((p_estimator ->> 'intercept')::double precision, 0))
  end
$$;

create or replace function public.submit_observations(payload jsonb) returns jsonb
language plpgsql volatile security definer
set search_path = ''
as $$
declare
  v_token text := private.request_header('x-node-token');
  v_node  public.nodes;
  v_items jsonb;
  v_item  jsonb;
  v_estimator jsonb;
  v_observed timestamptz;
  v_latest jsonb;
  v_latest_at timestamptz;
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
      v_node.id, v_node.room_id, v_observed, (v_item ->> 'window_sec')::int,
      v_item -> 'counts_by_rssi', v_item -> 'counts_by_vendor',
      nullif(v_item -> 'rssi_stats', 'null'::jsonb),
      (v_item ->> 'adv_count')::int, (v_item ->> 'battery')::int, (v_item ->> 'charging')::boolean,
      v_item ->> 'app_version', v_estimator
    )
    on conflict (node_id, observed_at) do nothing;
    get diagnostics v_rows = row_count;
    if v_rows = 1 then v_created := v_created + 1; else v_duplicate := v_duplicate + 1; end if;
  end loop;

  -- 最終受信はノードの生死判定に使うので、重複や部分的な窓だけでも更新する
  update public.nodes set
    last_seen_at = now(),
    battery = (v_latest ->> 'battery')::int,
    charging = (v_latest ->> 'charging')::boolean,
    app_version = v_latest ->> 'app_version'
  where id = v_node.id;

  return jsonb_build_object('created', v_created, 'duplicate', v_duplicate, 'ignored_partial', v_partial);
end;
$$;

-- 各教室の最新の混雑度とノードの状態
-- 教室の推定人数は、online のノードの最新の観測をそれぞれの式で推定した値の平均
create or replace function public.get_rooms() returns jsonb
language sql stable security definer
set search_path = ''
as $$
  with node_latest as (
    select n.*, n.last_seen_at > now() - interval '3 minutes' as online,
      o.observed_at as last_observed_at, o.counts_by_rssi, o.estimator
    from public.nodes n
    left join lateral (
      select o.observed_at, o.counts_by_rssi, o.estimator from public.observations o
      where o.node_id = n.id order by o.observed_at desc limit 1
    ) o on true
  ),
  per_room as (
    select r.id, r.name, p.*,
      (select avg(private.estimate_obs(nl.counts_by_rssi, nl.estimator, p.feature, p.coef, p.intercept))
         from node_latest nl where nl.room_id = r.id and nl.online) as people,
      (select max(nl.last_observed_at) from node_latest nl where nl.room_id = r.id) as updated_at
    from public.rooms r
    cross join lateral private.params_for(r.id) p
  )
  select jsonb_build_object(
    'now', now(),
    'rooms', coalesce(jsonb_agg(jsonb_build_object(
      'id', pr.id,
      'name', pr.name,
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
    ) order by pr.id), '[]'::jsonb)
  )
  from per_room pr
$$;

-- 観測時刻ごとの推定人数（各観測をその式で推定し、複数ノードは平均）と RSSI 閾値別端末数、実人数
create or replace function public.get_room_history(room_id text, from_ts timestamptz default null, to_ts timestamptz default null)
returns jsonb
language sql stable security definer
set search_path = ''
as $$
  -- 日本時間の今日の 0:00。日本は夏時間がないので +9 時間の固定オフセットで計算する
  with today as (
    select (date_trunc('day', (now() at time zone 'UTC') + interval '9 hours') - interval '9 hours') at time zone 'UTC' as d
  ),
  bounds as (
    select coalesce(from_ts, today.d) as f, coalesce(to_ts, today.d + interval '1 day') as t
    from today
  ),
  p as (select * from private.params_for(get_room_history.room_id)),
  points as (
    select o.observed_at,
      count(*) as nodes,
      avg(private.estimate_obs(o.counts_by_rssi, o.estimator, p.feature, p.coef, p.intercept)) as people,
      round(avg((o.counts_by_rssi ->> '-60')::numeric), 1) as c60,
      round(avg((o.counts_by_rssi ->> '-70')::numeric), 1) as c70,
      round(avg((o.counts_by_rssi ->> '-80')::numeric), 1) as c80,
      round(avg((o.counts_by_rssi ->> '-90')::numeric), 1) as c90,
      round(avg((o.counts_by_rssi ->> 'all')::numeric), 1) as call
    from public.observations o, bounds b, p
    where o.room_id = get_room_history.room_id and o.observed_at >= b.f and o.observed_at < b.t
    group by o.observed_at
  )
  select jsonb_build_object(
    'room_id', get_room_history.room_id,
    'from', b.f,
    'to', b.t,
    'feature', p.feature,
    'calibrated', p.calibrated,
    'threshold_moderate', p.threshold_moderate,
    'threshold_crowded', p.threshold_crowded,
    'points', coalesce((
      select jsonb_agg(jsonb_build_object(
        'observed_at', pt.observed_at,
        'estimated_people', round(pt.people::numeric, 1),
        'level', private.level_of(pt.people, p.threshold_moderate, p.threshold_crowded),
        'counts_by_rssi', jsonb_strip_nulls(jsonb_build_object(
          '-60', pt.c60, '-70', pt.c70, '-80', pt.c80, '-90', pt.c90, 'all', pt.call)),
        'nodes', pt.nodes
      ) order by pt.observed_at) from points pt), '[]'::jsonb),
    'ground_truth', coalesce((
      select jsonb_agg(jsonb_build_object('observed_at', g.observed_at, 'people', g.people, 'note', g.note)
                       order by g.observed_at)
      from public.ground_truth g
      where g.room_id = get_room_history.room_id and g.observed_at >= b.f and g.observed_at < b.t), '[]'::jsonb)
  )
  from bounds b, p
$$;

revoke execute on function private.estimate_obs(jsonb, jsonb, text, double precision, double precision) from public;
