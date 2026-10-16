-- ノードの監視（2026-10-17）
--
-- - ノードは観測値に端末の状態 diag（画面が点いているか、送信待ちの件数、スキャンの再起動回数）を添えて送る
-- - 10分ごとに、ノードごとの直近10分の状況を Discord の Webhook に投稿する（pg_cron + pg_net）
--   Webhook の URL は秘密情報なので、リポジトリに入れず、SQL Editor で private.set_discord_webhook('https://...') で設定する
-- - 報告は pg_cron が呼ぶ private.send_node_report()。本文は private.node_report_text() で作る

alter table public.observations add column diag jsonb;
alter table private.settings add column discord_webhook_url text;

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
  v_diag jsonb;
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

    -- 端末の状態（任意、監視用）。オブジェクトでなければ捨てる
    v_diag := case when jsonb_typeof(v_item -> 'diag') = 'object' then v_item -> 'diag' end;

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
      adv_count, battery, charging, app_version, estimator, diag
    ) values (
      v_node.id, v_room, v_observed, (v_item ->> 'window_sec')::int,
      v_item -> 'counts_by_rssi', v_item -> 'counts_by_vendor',
      nullif(v_item -> 'rssi_stats', 'null'::jsonb),
      (v_item ->> 'adv_count')::int, (v_item ->> 'battery')::int, (v_item ->> 'charging')::boolean,
      v_item ->> 'app_version', v_estimator, v_diag
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

-- 直近10分の状況。対象は、今の分の1分前で終わる10個の窓（送信が遅れて届く分を待つため）
-- 例: 08:10:00 に呼ぶと 07:59〜08:08 の窓を見る
create function private.node_report_text(p_now timestamptz default now()) returns text
language sql stable
set search_path = ''
as $$
  with b as (
    select date_trunc('minute', p_now) - interval '11 minutes' as f,
           date_trunc('minute', p_now) - interval '1 minute' as t
  ),
  per_node as (
    select n.id, n.last_seen_at, n.battery, n.charging, r.name as room_name,
      n.last_seen_at > p_now - interval '3 minutes' as online,
      count(o.id) as windows,
      count(o.id) filter (where o.received_at > o.observed_at + interval '3 minutes') as late,
      round(avg(o.adv_count)) as adv_per_min,
      round(avg((o.counts_by_rssi ->> 'all')::numeric)) as devices,
      round(avg(private.estimate_obs(o.counts_by_rssi, o.estimator, '-80', 1, 0))::numeric, 1) as people,
      (array_agg(o.diag order by o.observed_at desc) filter (where o.diag is not null))[1] as diag
    from public.nodes n
    join public.rooms r on r.id = n.room_id
    cross join b
    left join public.observations o on o.node_id = n.id and o.observed_at >= b.f and o.observed_at < b.t
    group by n.id, n.last_seen_at, n.battery, n.charging, r.name
  )
  select concat_ws(E'\n',
    '**ノード状況** ' || to_char(((select t from b) at time zone 'UTC' + interval '9 hours'), 'MM/DD HH24:MI')
      || '（直近10分 ' || to_char(((select f from b) at time zone 'UTC' + interval '9 hours'), 'HH24:MI') || '〜）',
    coalesce(string_agg(
      case when online then '🟢 ' else '🔴 ' end || '**' || id || '**｜' || room_name
      || '｜電池 ' || coalesce(battery::text || '%', '-') || case when charging then '（充電中）' when charging is false then '（**充電なし**）' else '' end
      || case when not online then '｜**停止**（最終受信 ' || coalesce(to_char(last_seen_at at time zone 'UTC' + interval '9 hours', 'MM/DD HH24:MI'), 'なし') || '）' else '' end
      || E'\n　窓 ' || windows || '/10'
      || case when windows < 10 then '（**欠け ' || (10 - windows) || '**）' else '' end
      || case when late > 0 then '｜遅れて届いた窓 ' || late else '' end
      || '｜' || coalesce(adv_per_min::text, '-') || ' pkt/分｜端末 ' || coalesce(devices::text, '-')
      || '台｜推定 ' || coalesce(people::text, '-') || '人'
      || case when diag is null then '' else
           E'\n　画面 ' || case when (diag ->> 'screen_on')::boolean then 'ON' else '**OFF**' end
           || '｜未送信 ' || coalesce(diag ->> 'queued', '-')
           || '｜無受信による再起動 ' || coalesce(diag ->> 'no_result_restarts', '-') || '回'
         end,
      E'\n' order by id), 'ノードが登録されていません'))
  from per_node
$$;

create function private.send_node_report() returns void
language plpgsql volatile
set search_path = ''
as $$
declare
  v_url text;
begin
  select s.discord_webhook_url into v_url from private.settings s;
  if v_url is null or v_url = '' then return; end if;
  -- Discord の1メッセージは2000文字まで
  perform net.http_post(
    url := v_url,
    body := jsonb_build_object('username', '混雑度ノード監視', 'content', left(private.node_report_text(), 1990)),
    headers := '{"Content-Type": "application/json"}'::jsonb
  );
end;
$$;

create function private.set_discord_webhook(p_url text) returns void
language sql
set search_path = ''
as $$ update private.settings set discord_webhook_url = nullif(p_url, '') $$;

revoke execute on function private.node_report_text(timestamptz) from public;
revoke execute on function private.send_node_report() from public;
revoke execute on function private.set_discord_webhook(text) from public;

-- 定期実行。pg_cron / pg_net のない環境（手元のテスト用 Postgres）では飛ばす
do $do$
begin
  create extension if not exists pg_net with schema extensions;
  create extension if not exists pg_cron;
  perform cron.schedule('node-report', '*/10 * * * *', 'select private.send_node_report()');
exception when others then
  raise notice 'pg_cron / pg_net が使えないため、定期送信を設定しませんでした: %', sqlerrm;
end
$do$;
