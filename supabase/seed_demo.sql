-- デモデータ：demo-3A（ノード1台）と demo-3B（ノード2台、うち1台は10分前に停止）に、
-- 今日（日本時間）8:00 から今までの1分ごとのダミー観測と、30分ごとの実人数を作る。
-- Supabase の SQL Editor に貼って実行する。何度実行しても作り直すだけ。
-- 消すときは末尾のコメントの delete 文を実行する。

create or replace function private.demo_people(p_minute int, p_capacity int) returns int
language plpgsql volatile
set search_path = ''
as $$
declare
  -- 時限の開始（0:00 からの分）。各50分
  starts int[] := array[530, 590, 650, 710, 800, 860, 920];
  s int;
  base double precision := 0;
begin
  foreach s in array starts loop
    if p_minute >= s - 10 and p_minute < s then          -- 授業前に集まってくる
      base := greatest(base, (p_minute - (s - 10)) / 10.0 * 0.9);
    elsif p_minute >= s and p_minute < s + 50 then       -- 授業中
      base := greatest(base, 0.95);
    elsif p_minute >= s + 50 and p_minute < s + 60 then  -- 休み時間
      base := greatest(base, 0.4);
    end if;
  end loop;
  if p_minute >= 760 and p_minute < 790 then              -- 昼休み
    base := greatest(base, 0.5);
  end if;
  return greatest(0, round(p_capacity * base + 1.5 * private.demo_gauss()));
end;
$$;

-- 標準正規乱数（Box–Muller。random_normal() のない Postgres でも動くように）
create or replace function private.demo_gauss() returns double precision
language sql volatile
set search_path = ''
as $$ select sqrt(-2 * ln(1 - random())) * cos(2 * pi() * random()) $$;

-- 人数から1窓分の集計値を作る。1人あたり平均1.3台の BLE 端末＋教室外・据え置き機器のノイズ
create or replace function private.demo_counts(p_people int) returns jsonb
language plpgsql volatile
set search_path = ''
as $$
declare
  rssi double precision[];
  n int := greatest(0, floor(p_people * 1.3 + private.demo_gauss()))::int;
  bg int := 6 + floor(random() * 9)::int;
  vendor jsonb;
begin
  select array_agg(v order by v) into rssi from (
    select -72 + 9 * private.demo_gauss() as v from generate_series(1, n)
    union all
    select -90 + 5 * private.demo_gauss() from generate_series(1, bg)
  ) d;
  vendor := jsonb_build_object(
    'apple', round(cardinality(rssi) * 0.45), 'google', round(cardinality(rssi) * 0.08),
    'microsoft', round(cardinality(rssi) * 0.06), 'samsung', round(cardinality(rssi) * 0.05),
    'other', round(cardinality(rssi) * 0.16), 'none', round(cardinality(rssi) * 0.20));
  return jsonb_build_object(
    'counts_by_rssi', jsonb_build_object(
      '-60', (select count(*) from unnest(rssi) v where v >= -60),
      '-70', (select count(*) from unnest(rssi) v where v >= -70),
      '-80', (select count(*) from unnest(rssi) v where v >= -80),
      '-90', (select count(*) from unnest(rssi) v where v >= -90),
      'all', cardinality(rssi)),
    'counts_by_vendor', vendor,
    'rssi_stats', jsonb_build_object(
      'median', round(rssi[cardinality(rssi) / 2 + 1]),
      'p10', round(rssi[floor(cardinality(rssi) * 0.1)::int + 1]),
      'p90', round(rssi[least(cardinality(rssi), floor(cardinality(rssi) * 0.9)::int + 1)])));
end;
$$;

do $$
declare
  -- 日本時間の今日 8:00 と、今（分に切り捨て）
  now_min timestamptz := date_trunc('minute', now());
  day_start timestamptz := (date_trunc('day', (now() at time zone 'UTC') + interval '9 hours') - interval '9 hours') at time zone 'UTC';
  t_start timestamptz := day_start + interval '8 hours';
  t_end timestamptz := now_min;
  t timestamptz;
  minute_of_day int;
  people int;
  c jsonb;
  r record;
  node_idx int;
  node_id text;
begin
  -- 朝8時前に実行したときは前日の 8:00〜18:00 を作る
  if now_min < t_start then
    t_start := t_start - interval '1 day';
    t_end := t_start + interval '10 hours';
  end if;

  delete from public.observations where room_id in ('demo-3A', 'demo-3B');
  delete from public.ground_truth where room_id in ('demo-3A', 'demo-3B');
  delete from public.calibrations where room_id in ('demo-3A', 'demo-3B');
  delete from public.nodes where room_id in ('demo-3A', 'demo-3B');
  insert into public.rooms (id, name) values ('demo-3A', 'デモ 3年A組'), ('demo-3B', 'デモ 3年B組')
    on conflict (id) do update set name = excluded.name;
  -- デモノードのトークンは捨てる（使うときは private.rotate_node_token で発行し直す）
  insert into public.nodes (id, room_id, token_hash) values
    ('demo-3A-01', 'demo-3A', private.hash_token(private.new_token())),
    ('demo-3B-01', 'demo-3B', private.hash_token(private.new_token())),
    ('demo-3B-02', 'demo-3B', private.hash_token(private.new_token()));

  for r in select * from (values ('demo-3A', 32, array['demo-3A-01']),
                                 ('demo-3B', 20, array['demo-3B-01', 'demo-3B-02'])) v(room_id, cap, nodes) loop
    t := t_start;
    while t <= t_end loop
      minute_of_day := extract(epoch from (t - day_start))::int / 60 % 1440;
      people := private.demo_people(minute_of_day, r.cap);
      for node_idx in 1 .. cardinality(r.nodes) loop
        node_id := r.nodes[node_idx];
        -- 2台目のノードは10分前に止まった想定（停止表示の確認用）
        continue when node_idx = 2 and t > t_end - interval '10 minutes';
        c := private.demo_counts(people);
        insert into public.observations (node_id, room_id, observed_at, received_at, window_sec,
          counts_by_rssi, counts_by_vendor, rssi_stats, battery, charging, app_version)
        values (node_id, r.room_id, t, t + interval '61 seconds', 60,
          c -> 'counts_by_rssi', c -> 'counts_by_vendor', c -> 'rssi_stats', 100, true, 'demo');
        update public.nodes set last_seen_at = t + interval '61 seconds', battery = 100, charging = true, app_version = 'demo'
          where id = node_id;
      end loop;
      if extract(minute from t)::int % 30 = 5 then
        insert into public.ground_truth (room_id, observed_at, people, note) values (r.room_id, t, people, 'demo');
      end if;
      t := t + interval '1 minute';
    end loop;
  end loop;
end;
$$;

-- 片付け：
-- delete from public.observations where room_id like 'demo-%';
-- delete from public.ground_truth where room_id like 'demo-%';
-- delete from public.calibrations where room_id like 'demo-%';
-- delete from public.nodes where room_id like 'demo-%';
-- delete from public.rooms where id like 'demo-%';
