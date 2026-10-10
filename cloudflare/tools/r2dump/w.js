export default { async fetch(req, env) {
  const u = new URL(req.url); const prefix = u.searchParams.get("p") || "";
  if (u.searchParams.get("k")) { const o = await env.R.get(u.searchParams.get("k")); return o ? new Response(o.body) : new Response("", {status:404}); }
  let cursor, keys = [];
  do { const r = await env.R.list({prefix, cursor, limit:1000}); keys.push(...r.objects.map(o=>({key:o.key,size:o.size}))); cursor = r.truncated ? r.cursor : undefined; } while (cursor);
  return Response.json(keys);
}}
