-- BLE教室混雑度推定システム：Supabase（Postgres）のスキーマ
--
-- 公開キー（anon / publishable key）でできること
--   - rooms / observations / ground_truth / calibrations の SELECT（すべて集計値で、個人を識別できる値は含まない）
--   - RPC: get_rooms / get_room_history（画面表示用）
--   - RPC: submit_observations（ヘッダ x-node-token にノードのトークンが必要）
--   - RPC: submit_ground_truth（ヘッダ x-admin-token に管理トークンが必要）
-- 管理操作（教室・ノードの登録、トークン発行、校正値の登録）は SQL Editor から private.* の関数で行う。
-- private スキーマは Data API に公開されない。

create schema if not exists private;
revoke all on schema private from public;

-- ---------------------------------------------------------------- テーブル

create table public.rooms (
  id   text primary key,
  name text not null
);

create table public.nodes (
  id           text primary key,
  room_id      text not null references public.rooms (id),
  -- トークンは SHA-256 の16進文字列で保存し、平文は持たない
  token_hash   text not null unique,
  created_at   timestamptz not null default now(),
  -- 最終受信時刻（再送分を受けたときも更新）。offline 判定に使う
  last_seen_at timestamptz,
  battery      int,
  charging     boolean,
  app_version  text
);

create table public.observations (
  id               bigint generated always as identity primary key,
  node_id          text not null references public.nodes (id),
  -- 受信時点のノードの所属教室（ノードを移しても過去のデータは元の教室のまま）
  room_id          text not null references public.rooms (id),
  observed_at      timestamptz not null,
  received_at      timestamptz not null default now(),
  window_sec       int not null check (window_sec > 0),
  counts_by_rssi   jsonb not null,
  counts_by_vendor jsonb not null,
  rssi_stats       jsonb,
  adv_count        int,
  battery          int,
  charging         boolean,
  app_version      text,
  -- 再送で同じ窓が2回届いても1件にする
  unique (node_id, observed_at)
);
create index observations_room_time on public.observations (room_id, observed_at);

create table public.ground_truth (
  id          bigint generated always as identity primary key,
  room_id     text not null references public.rooms (id),
  observed_at timestamptz not null,
  people      int not null check (people >= 0),
  note        text,
  created_at  timestamptz not null default now()
);
create index ground_truth_room_time on public.ground_truth (room_id, observed_at);

-- 教室ごとの推定式と段階の閾値。同じ教室の最新の行を使う
create table public.calibrations (
  id                 bigint generated always as identity primary key,
  room_id            text not null references public.rooms (id),
  -- counts_by_rssi のキー
  feature            text not null check (feature in ('-60', '-70', '-80', '-90', 'all')),
  coef               double precision not null,
  intercept          double precision not null default 0,
  -- 推定人数がこれ以上なら「やや混雑」
  threshold_moderate double precision not null,
  -- 推定人数がこれ以上なら「混雑」
  threshold_crowded  double precision not null,
  note               text,
  created_at         timestamptz not null default now()
);

-- 管理トークン（ハッシュ）。1行だけ
create table private.settings (
  id               boolean primary key default true check (id),
  admin_token_hash text
);
insert into private.settings default values;

-- ---------------------------------------------------------------- RLS

alter table public.rooms enable row level security;
alter table public.nodes enable row level security;
alter table public.observations enable row level security;
alter table public.ground_truth enable row level security;
alter table public.calibrations enable row level security;

-- 読み取りだけ公開する。書き込みのポリシーは作らない（書き込みは RPC 経由のみ）
-- nodes はトークンのハッシュを含むので公開しない（状態は get_rooms で返す）
create policy "公開で読める" on public.rooms for select to anon, authenticated using (true);
create policy "公開で読める" on public.observations for select to anon, authenticated using (true);
create policy "公開で読める" on public.ground_truth for select to anon, authenticated using (true);
create policy "公開で読める" on public.calibrations for select to anon, authenticated using (true);

-- ---------------------------------------------------------------- 内部の関数

create function private.hash_token(token text) returns text
language sql immutable
set search_path = ''
as $$ select encode(sha256(convert_to(token, 'UTF8')), 'hex') $$;

create function private.new_token() returns text
language sql volatile
set search_path = ''
as $$ select replace(gen_random_uuid()::text || gen_random_uuid()::text, '-', '') $$;

-- PostgREST が渡すリクエストヘッダ（名前は小文字）
create function private.request_header(name text) returns text
language sql stable
set search_path = ''
as $$ select nullif(current_setting('request.headers', true), '')::jsonb ->> name $$;

-- 教室の推定パラメータ。calibrations に行がなければ校正前の既定値
-- 既定値：-80 dBm 以上の端末数 × 1 + 0、閾値は やや混雑 15人 / 混雑 30人（仮の値）
create function private.params_for(p_room_id text)
returns table (feature text, coef double precision, intercept double precision,
               threshold_moderate double precision, threshold_crowded double precision, calibrated boolean)
language sql stable
set search_path = ''
as $$
  select * from (
    select c.feature, c.coef, c.intercept, c.threshold_moderate, c.threshold_crowded, true
    from public.calibrations c
    where c.room_id = p_room_id
    order by c.created_at desc, c.id desc
    limit 1
  ) latest
  union all
  select '-80', 1.0, 0.0, 15.0, 30.0, false
  where not exists (select 1 from public.calibrations c where c.room_id = p_room_id)
$$;

create function private.estimate(feature_value double precision, coef double precision, intercept double precision)
returns double precision
language sql immutable
set search_path = ''
as $$ select case when feature_value is null then null else greatest(0, coef * feature_value + intercept) end $$;

create function private.level_of(people double precision, moderate double precision, crowded double precision)
returns text
language sql immutable
set search_path = ''
as $$
  select case
    when people is null then null
    when people >= crowded then 'crowded'
    when people >= moderate then 'moderate'
    else 'empty'
  end
$$;

-- ---------------------------------------------------------------- 公開 RPC

-- ノードからの観測値。payload は1件のオブジェクトか、その配列（再送分をまとめて送れる）
-- 返り値：{"created": n, "duplicate": n, "ignored_partial": n}
create function public.submit_observations(payload jsonb) returns jsonb
language plpgsql volatile security definer
set search_path = ''
as $$
declare
  v_token text := private.request_header('x-node-token');
  v_node  public.nodes;
  v_items jsonb;
  v_item  jsonb;
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
      adv_count, battery, charging, app_version
    ) values (
      v_node.id, v_node.room_id, v_observed, (v_item ->> 'window_sec')::int,
      v_item -> 'counts_by_rssi', v_item -> 'counts_by_vendor',
      nullif(v_item -> 'rssi_stats', 'null'::jsonb),
      (v_item ->> 'adv_count')::int, (v_item ->> 'battery')::int, (v_item ->> 'charging')::boolean,
      v_item ->> 'app_version'
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

-- 実人数の入力（校正用）。ヘッダ x-admin-token が必要。observed_at を省くと受信時刻
create function public.submit_ground_truth(
  room_id text, people int, observed_at timestamptz default null, note text default null
) returns jsonb
language plpgsql volatile security definer
set search_path = ''
as $$
declare
  v_token text := private.request_header('x-admin-token');
  v_hash text;
  v_row public.ground_truth;
begin
  select s.admin_token_hash into v_hash from private.settings s;
  if v_hash is null then
    raise exception '管理トークンが未設定です（SQL Editor で private.set_admin_token を実行）' using errcode = '28000';
  end if;
  if v_token is null or private.hash_token(v_token) <> v_hash then
    raise exception '管理トークンが違います' using errcode = '28000';
  end if;
  if not exists (select 1 from public.rooms r where r.id = submit_ground_truth.room_id) then
    raise exception '教室 % がありません', submit_ground_truth.room_id using errcode = '22023';
  end if;
  if people is null or people < 0 then
    raise exception 'people は0以上' using errcode = '22023';
  end if;
  insert into public.ground_truth (room_id, observed_at, people, note)
  values (submit_ground_truth.room_id, coalesce(submit_ground_truth.observed_at, now()), people, note)
  returning * into v_row;
  return jsonb_build_object('id', v_row.id, 'room_id', v_row.room_id, 'observed_at', v_row.observed_at,
                            'people', v_row.people, 'note', v_row.note);
end;
$$;

-- 各教室の最新の混雑度とノードの状態
-- ノードは最終受信から3分を超えると offline。教室の推定は online のノードの最新値の平均
create function public.get_rooms() returns jsonb
language sql stable security definer
set search_path = ''
as $$
  with node_latest as (
    select n.*, n.last_seen_at > now() - interval '3 minutes' as online, o.observed_at as last_observed_at, o.counts_by_rssi
    from public.nodes n
    left join lateral (
      select o.observed_at, o.counts_by_rssi from public.observations o
      where o.node_id = n.id order by o.observed_at desc limit 1
    ) o on true
  ),
  per_room as (
    select r.id, r.name, p.*,
      (select avg(private.estimate((nl.counts_by_rssi ->> p.feature)::double precision, p.coef, p.intercept))
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
          'battery', nl.battery, 'charging', nl.charging, 'app_version', nl.app_version
        ) order by nl.id), '[]'::jsonb)
        from node_latest nl where nl.room_id = pr.id)
    ) order by pr.id), '[]'::jsonb)
  )
  from per_room pr
$$;

-- 観測時刻ごとの推定人数（複数ノードは平均）と RSSI 閾値別端末数、実人数
-- from_ts / to_ts を省くと今日（日本時間）の 0:00〜24:00
create function public.get_room_history(room_id text, from_ts timestamptz default null, to_ts timestamptz default null)
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
  per_time as (
    select o.observed_at,
      count(*) as nodes,
      round(avg((o.counts_by_rssi ->> '-60')::numeric), 1) as c60,
      round(avg((o.counts_by_rssi ->> '-70')::numeric), 1) as c70,
      round(avg((o.counts_by_rssi ->> '-80')::numeric), 1) as c80,
      round(avg((o.counts_by_rssi ->> '-90')::numeric), 1) as c90,
      round(avg((o.counts_by_rssi ->> 'all')::numeric), 1) as call
    from public.observations o, bounds b
    where o.room_id = get_room_history.room_id and o.observed_at >= b.f and o.observed_at < b.t
    group by o.observed_at
  ),
  points as (
    select pt.*, private.estimate(
      case p.feature when '-60' then pt.c60 when '-70' then pt.c70 when '-80' then pt.c80
                     when '-90' then pt.c90 else pt.call end, p.coef, p.intercept) as people
    from per_time pt, p
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

-- Supabase は public の関数を既定で anon にも実行させるので、明示的に絞る
revoke execute on all functions in schema public from public, anon, authenticated;
grant execute on function public.submit_observations(jsonb) to anon, authenticated;
grant execute on function public.submit_ground_truth(text, int, timestamptz, text) to anon, authenticated;
grant execute on function public.get_rooms() to anon, authenticated;
grant execute on function public.get_room_history(text, timestamptz, timestamptz) to anon, authenticated;
revoke execute on all functions in schema private from public;

-- ---------------------------------------------------------------- 管理用（SQL Editor から実行）
--   select private.add_room('room-3A', '3年A組');
--   select private.add_node('room-3A-01', 'room-3A');     -- トークンが返る（再表示不可）
--   select private.rotate_node_token('room-3A-01');
--   select private.set_admin_token('長いランダム文字列');
--   select private.set_calibration('room-3A', '-80', 0.8, 0, 15, 30, 'メモ');

create function private.add_room(p_id text, p_name text) returns void
language sql
set search_path = ''
as $$ insert into public.rooms (id, name) values (p_id, p_name) $$;

create function private.add_node(p_node_id text, p_room_id text) returns text
language plpgsql
set search_path = ''
as $$
declare v_token text := private.new_token();
begin
  insert into public.nodes (id, room_id, token_hash) values (p_node_id, p_room_id, private.hash_token(v_token));
  return v_token;
end;
$$;

create function private.rotate_node_token(p_node_id text) returns text
language plpgsql
set search_path = ''
as $$
declare v_token text := private.new_token();
begin
  update public.nodes set token_hash = private.hash_token(v_token) where id = p_node_id;
  if not found then raise exception 'ノード % がありません', p_node_id; end if;
  return v_token;
end;
$$;

create function private.set_admin_token(p_token text) returns void
language sql
set search_path = ''
as $$ update private.settings set admin_token_hash = private.hash_token(p_token) $$;

create function private.set_calibration(
  p_room_id text, p_feature text, p_coef double precision, p_intercept double precision,
  p_moderate double precision, p_crowded double precision, p_note text default null
) returns void
language sql
set search_path = ''
as $$
  insert into public.calibrations (room_id, feature, coef, intercept, threshold_moderate, threshold_crowded, note)
  values (p_room_id, p_feature, p_coef, p_intercept, p_moderate, p_crowded, p_note)
$$;
