# TokenNest 登录配置

AI Bot Assist 使用公共原生客户端的 Authorization Code + PKCE S256，玩家在系统浏览器授权。
没有 client_secret，access/refresh token 只保存在玩家客户端当前世界的加密凭据库。

## 服务器注册参数

| 项目 | 值 |
| --- | --- |
| 应用名称 | AI Bot Assist |
| 项目默认 client_id | `tnc_eqezq1pCPAZ4oOkU90ZEchhiyvFf3Pks`（用户提供的后台客户端 ID） |
| 注册的 Redirect URI | `http://127.0.0.1/aibotassist/callback` |
| Allowed scopes | `api offline_access groups:read` |
| 客户端类型 | public / native |
| Grant types | `authorization_code`、`refresh_token` |
| Response type | `code` |
| PKCE | 必须，`S256` |
| Token endpoint auth method | `none` |
| Client secret | 不设置 |
| Issuer | `https://openai.goldgom.top` |
| Authorization endpoint | `https://openai.goldgom.top/oauth/authorize` |
| Token endpoint | `https://openai.goldgom.top/api/oauth2/token` |
| Revocation endpoint | `https://openai.goldgom.top/api/oauth2/revoke` |
| 模型 API | `https://openai.goldgom.top/v1` |

注册 URI **不填写端口、通配符、查询参数或末尾斜杠**。模组实际请求的回调类似
`http://127.0.0.1:54321/aibotassist/callback`；端口由操作系统动态分配。
TokenNest 的无端口 loopback 注册规则允许这个端口变化，主机和路径必须一致。
回调监听在玩家电脑的 IPv4 loopback，不需要给 Minecraft 服务器开放端口或配置 HTTPS 回调代理。

## 当前客户端

项目已使用上述用户提供的 client_id。后台请核对该客户端已启用，并配置表中的 Redirect URI 和 scopes。
新配置直接使用此 ID；已有配置中的空 ID 或旧占位值 `tnc_aibotassist` 在加载时自动替换。
玩家手动配置的其他 client_id 保留。

## 需要另外创建客户端时

在 TokenNest 管理后台的 OAuth 客户端管理中创建并启用应用，填写名称、Redirect URI 和上述 scopes。
也可使用管理员身份调用 `POST /api/oauth2/clients`，请求正文见 [tokennest-client.json](tokennest-client.json)。
该接口会自动生成一个 `tnc_...` ID，不能通过传入 client_id 指定固定 ID。

创建后，把返回的真实 ID 填到游戏内 `/aibot settings` → “登录 / Pi运行时” → “TokenNest client_id”，
替换项目默认值。不要复用 TokenBird/Copilot 的 client_id。

## 玩家使用与验收

1. 进入游戏世界，执行 `/aibot settings`。模型 ID 可留空，登录后自动从账户模型列表选择兼容 Chat / Responses 的首个模型；列表刷新时保留仍可用的模型，不再保留已不可用的旧选择。
2. 如果后台生成了其他 client_id，先到“登录 / Pi运行时”页填写并保存。
3. 点击“TokenNest 登录”，API 地址自动切换为 TokenNest `/v1`。在系统浏览器登录、同意授权。
4. 回调页出现 `Signed in`。游戏获取模型后显示“可用：模型ID”，可用“选择模型”按钮切换；模型查询失败会保留登录凭据并显示模型获取错误，不会再提示重新登录。
5. 退出并重新进入同一世界，凭据继续可用。已有登录但模型为空的旧配置会自动补选模型；首次聊天也会补选并继续处理原消息。
6. 在聊天中输入 `@AI 你好`，再测试 `@AI 看看周围有什么`，验证模型和工具请求。

登录等待 3 分钟，可在高级页取消后重新发起。错误 state、重复参数和其他无效回调不会终止当前合法登录。
玩家切换世界、清除凭据、更换 API/client_id 时，原登录等待会取消。
短期 access token 将过期时自动通过 refresh_token 刷新并保存；OAuth 凭据禁止向自定义 API 地址发送。

“清除当前世界凭据”仅删除本机当前世界记录。若要撤销服务器端授权，在 TokenNest 账户的已授权应用中撤销。
高级页的“复制 OAuth 配置信息”可复制当前 client_id、注册 URI、scopes 和端点。

## 验证范围

公开 OAuth metadata 及本机 TokenNest 源码用于核对服务端协议；自动测试覆盖 PKCE、回调校验、
本地 HTTP 回调和取消、跨世界隔离、OAuth 凭据的目标地址限制。
服务器创建客户端后仍需按上面的步骤完成一次真实浏览器授权、换取令牌及模型请求验收。

## 渠道与模型接口

在「设置 → 渠道/类型」中选择或填写 TokenNest 渠道，默认 `default`。获取渠道调用 OAuth 的 `/api/oauth2/groups`；修改渠道后，模型查询和模型请求均携带 `X-TokenNest-Group`，重新检查可用模型。没有权限的渠道由服务端拒绝，失败保留本地登录凭据。

后台此客户端的 Allowed scopes 需包含 `groups:read`；已有授权需重新登录同意新范围，才能获取渠道列表。手动填写渠道无需渠道查询权限。普通 API Key 仍按密钥自身绑定的渠道计费和路由，TokenNest 会忽略它的渠道请求头；使用相应渠道的密钥或切换到 OAuth 登录。

模型类型默认 `auto`，根据目录中的 `supported_endpoint_types` 选择 Chat Completions 或 Responses，选择器显示接口类型。Responses 支持工具调用、工具反馈及视野/地图图片。可手动指定 `chat` 或 `responses` 以连接自定义模型；未提供目录的通用服务默认按 Chat 处理。仅图片生成、嵌入等模型不出现在可用列表，未实现 Anthropic/Gemini 原生协议。

多人共享模式由管理员在「服务器 API → 渠道/类型」固定渠道和接口，默认 `default` / `chat`。玩家个人渠道不能覆盖服务器配置；共享 API Key 仍使用密钥本身绑定的 TokenNest 渠道。
