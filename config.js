// 接続先の Supabase。publishable key はブラウザに公開する前提のキー（読み取りと RPC の呼び出しだけができる。
// 書き込みはノードトークン・管理トークンで守っている）なので、リポジトリに含めてよい
window.CROWD_CONFIG = {
  supabaseUrl: "https://vxhbjnbsrfopczqxqbhp.supabase.co",
  supabaseKey: "sb_publishable_w5olGvZMCO96K-nPwrZ6cQ_koLNE93d",
};
