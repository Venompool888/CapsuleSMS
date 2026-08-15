function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

function shell(title, body, user = null) {
  return `<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${escapeHtml(title)} · 胶囊短信同步</title><link rel="stylesheet" href="/assets/style.css"></head>
<body><header><div><strong>胶囊短信同步</strong><span class="muted">私有中继服务器</span></div>${user ? `<nav><a href="/portal">设备</a>${user.role === "admin" ? `<a href="/admin">用户</a>` : ""}<form method="post" action="/logout"><input type="hidden" name="csrf" value="${escapeHtml(user.csrf)}"><button class="link">退出</button></form></nav>` : ""}</header>
<main>${body}</main><footer>服务器只中继客户端生成的加密信封，不应接收明文短信。</footer></body></html>`;
}

export function loginPage(message = "") {
  return shell("登录", `<section class="card narrow"><h1>登录管理界面</h1><p class="muted">管理员可管理用户；普通用户可管理自己的设备和同步关系。</p>${message ? `<div class="alert">${escapeHtml(message)}</div>` : ""}<form method="post" action="/login"><label>用户名<input name="username" autocomplete="username" required></label><label>密码<input type="password" name="password" autocomplete="current-password" required></label><button type="submit">登录</button></form></section>`);
}

export function adminPage(user, users, message = "") {
  const rows = users.map((entry) => `<tr><td>${escapeHtml(entry.username)}</td><td>${entry.role === "admin" ? "管理员" : "用户"}</td><td>${entry.device_count}</td><td>${entry.enabled ? "正常" : "停用"}</td><td>${entry.role === "admin" ? "—" : `<div class="actions"><form method="post" action="/admin/users/${entry.id}/toggle"><input type="hidden" name="csrf" value="${escapeHtml(user.csrf)}"><button class="secondary">${entry.enabled ? "停用" : "启用"}</button></form><form class="password-reset" method="post" action="/admin/users/${entry.id}/password"><input type="hidden" name="csrf" value="${escapeHtml(user.csrf)}"><input type="password" name="password" minlength="12" required placeholder="新密码"><button class="secondary">重置密码</button></form></div>`}</td></tr>`).join("");
  return shell("用户管理", `<div class="title-row"><div><h1>用户管理</h1><p class="muted">创建账号、停用账号并查看设备数量。</p></div></div>${message ? `<div class="alert success">${escapeHtml(message)}</div>` : ""}<section class="card"><h2>新增用户</h2><form class="inline-form" method="post" action="/admin/users"><input type="hidden" name="csrf" value="${escapeHtml(user.csrf)}"><label>用户名<input name="username" required placeholder="yuri"></label><label>初始密码<input type="password" name="password" minlength="12" required></label><button type="submit">创建</button></form></section><section class="card"><table><thead><tr><th>用户名</th><th>角色</th><th>设备</th><th>状态</th><th></th></tr></thead><tbody>${rows}</tbody></table></section>`, user);
}

export function portalPage(user, devices, routes, message = "") {
  const routeSet = new Set(routes.map((route) => `${route.source_device_id}:${route.target_device_id}`));
  const cards = devices.map((device) => {
    const others = devices.filter((other) => other.id !== device.id && other.enabled);
    const push = others.map((other) => checkbox("pushTo", other, routeSet.has(`${device.id}:${other.id}`))).join("");
    const receive = others.map((other) => checkbox("receiveFrom", other, routeSet.has(`${other.id}:${device.id}`))).join("");
    return `<section class="card device"><div class="title-row"><div><h2>${escapeHtml(device.name)}</h2><p class="muted">${escapeHtml(device.platform)} · ${device.enabled ? "已连接" : "已撤销"}${device.last_seen_at ? ` · 最后在线 ${new Date(device.last_seen_at).toLocaleString("zh-CN")}` : ""}</p></div>${device.enabled ? `<form method="post" action="/portal/devices/${device.id}/revoke"><input type="hidden" name="csrf" value="${escapeHtml(user.csrf)}"><button class="danger">撤销</button></form>` : ""}</div>${device.enabled ? `<form method="post" action="/portal/routes"><input type="hidden" name="csrf" value="${escapeHtml(user.csrf)}"><input type="hidden" name="deviceId" value="${device.id}"><div class="route-grid"><fieldset><legend>推送短信到</legend>${push || `<p class="muted">暂无其他设备</p>`}</fieldset><fieldset><legend>接收来自</legend>${receive || `<p class="muted">暂无其他设备</p>`}</fieldset></div><button type="submit">保存同步设置</button></form>` : ""}</section>`;
  }).join("");
  return shell("设备与同步", `<div class="title-row"><div><h1>设备与同步</h1><p class="muted">任意一端修改推送关系，另一端的接收关系会自动同步。</p></div><form method="post" action="/portal/enrollments"><input type="hidden" name="csrf" value="${escapeHtml(user.csrf)}"><button>添加设备</button></form></div>${message ? `<div class="alert success">${escapeHtml(message)}</div>` : ""}${cards || `<section class="empty"><h2>还没有设备</h2><p>点击“添加设备”生成二维码或手动注册码。</p></section>`}`, user);
}

export function enrollmentPage(user, enrollment, qrSvg, baseUrl) {
  return shell("添加设备", `<section class="card enrollment"><h1>添加设备</h1><p>在胶囊短信的“云备份与同步”中扫描二维码，或手动输入服务器地址和注册码。</p><div class="qr">${qrSvg}</div><dl><dt>服务器地址</dt><dd><code>${escapeHtml(baseUrl)}</code></dd><dt>手动注册码</dt><dd><code class="code">${escapeHtml(enrollment.code)}</code></dd><dt>有效期</dt><dd>${new Date(enrollment.expiresAt).toLocaleString("zh-CN")}</dd></dl><p class="muted">注册码仅可使用一次。注册完成后，设备使用独立令牌连接，不保存账号密码。</p><a class="button secondary" href="/portal">返回设备列表</a></section>`, user);
}

function checkbox(name, device, checked) {
  return `<label class="check"><input type="checkbox" name="${name}" value="${device.id}" ${checked ? "checked" : ""}><span>${escapeHtml(device.name)}<small>${escapeHtml(device.platform)}</small></span></label>`;
}
