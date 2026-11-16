# AI 供应商与模型

新增供应商现在使用居中选择页面，滚动列表不会下拉关闭；用明确关闭或返回退出。每个供应商可选原文、中文或英文，UI 与 config `providers.<id>.language` 共用设置。

选择中文或英文前，先在离线翻译设置下载对应方向的固定版本模型包。显式选择语言而模型缺失或推理失败时会说明原因，不会悄悄发送未翻译原文；关闭全局翻译或选原文则保持原生请求。系统提示与消息中的自然语言按所选方向投影，代码、JSON、路径、工具参数、图片及签名保留。回复中文翻译只用于显示，原始模型内容仍保留给后续工具与签名协议。

Aharou 自己不提供模型，你需要接入至少一个模型服务才能开始对话。这一页讲怎么配好它。

入口在「设置 → AI 供应商管理」。

## 添加一个供应商

点右上角 +，会从底部弹出添加面板。列表第一项是**自定义供应商**（从空白手动配置），下面是内置的官方供应商（OpenAI、Anthropic、DeepSeek、智谱、通义等），两者样式一致。选内置供应商后自动填入名称、上游格式与 Base URL，进编辑页补上 API Key 就能用，也可以手动修改各项配置。

自定义配置时，填三样东西就能用：**上游格式**、**API Key**、**Base URL**。

- **上游格式**：`OpenAI` / `Anthropic` / `Gemini` 三选一，决定用哪种协议发请求。用第三方中转站的话，基本都选 `OpenAI`。
- **API Key**：你的密钥，形如 `sk-xxx`。粘贴时不用担心多带了空格或换行，保存时会自动清理。
- **Base URL**：只填 API 根地址，**不要带 `/v1` 或后面的具体路径**。留空会按上游格式自动填官方地址。
- **请求路径**：不用手动填，由**上游格式**自动推导——`OpenAI` 走 `v1/chat/completions`、`Anthropic` 走 `v1/messages`、`Gemini` 走 `v1beta`。若你的 Base URL 本身就是完整请求地址、不想再自动拼接，可在编辑页「选项」里打开**完整 URL** 开关。
- **名称**：给这个配置起个好认的名字，比如"我的中转"。列表里会按名字自动匹配品牌图标（填 DeepSeek 就显示 DeepSeek 的图标）。想用自己的图标，去「选项」里点「供应商图标」，可以从相册选一张图，也可以直接填图片链接；清空就恢复成按名字自动匹配的内置图标。自定义图标是本地文件，不包含在备份里：恢复备份后如果那张图不在本机了，会自动回落成按名字匹配的内置图标。

配完切到「模型」标签页拉取模型，就可以回主页开始对话了。

## 账号网页登录与当前查询

豆包与百度搭子的本人网页登录在隔离窗口内完成。点击完成后先等待账号和模型目录验证，只有本机凭据写入成功才关闭窗口。网络或账号验证失败时页面保留，可检查网络及登录状态后在同一窗口重试；接收登录信息不等于保存成功。取消后不接受迟到结果。

登录窗口无法启动、模型目录为空及返回格式无法识别分别显示错误，不以旧目录或假余额替代当前查询。Trae 当前目录使用与参考网关一致的请求上下文；真实账号是否可用仍取决于服务方响应和本人验证。

## Trae、WorkBuddy 与小浣熊账号

在添加供应商面板中选择 **Trae 国内版**、**WorkBuddy 国内版** 或 **小浣熊**，进入编辑页后点「登录」。授权页在应用内打开；只在服务方页面输入自己的登录信息，不需要把密码或令牌交给 AI。完成授权后返回供应商页，会查询账号当前的模型、倍率和积分，并将模型加入该供应商。

- **积分与倍率**：手动点「刷新」重新查询；倍率缺失显示未知，不表示免费。WorkBuddy 展示周期额度的剩余／总量，不把资源池总额当作当前可用余额。查询失败时保留上次值与时间，不当作当前数据。
- **实际调用**：仍在原有聊天模型选择器中选择模型，文本与工具调用沿用原有工作流。不另开网关服务，也不需要安装「网关管家」。模型是否可调用由账号权限和上游当前目录决定。
- **登录有效期**：接口返回可刷新凭据时按需刷新；失效、账号限制和渠道拒绝会报错，不会通过换设备或改写对话绕过限制。重新登录会替换本机该供应商的登录态。
- **凭据与备份**：账号凭据用设备 Keystore 加密保存在应用私有目录，不写入 API Key、供应商请求头或日志。普通供应商备份不包含这份账号登录态，在新设备恢复后需重新登录。删除供应商或点「退出登录」会清除本机凭据，不注销远端账号。
- **配置工具**：`providers.<id>.account_service` 可选择 `trae`、`workbuddy`、`raccoon`；登录仍须由本人完成。不提供经通用配置审计读取或导入账号令牌的字段，避免在配置历史中留下可复用凭据；退出登录在供应商页面完成。

这是第三方兼容接入，不代表服务方提供或承诺长期稳定的开放 API。授权、模型可见性、倍率、积分和调用能力都以上游实际返回为准；不保证所有账号、套餐或模型都可使用。

## Fast / Ultrafast 速度档位

在 OpenAI 格式供应商编辑页的「选项 → 速度档位」中选择：

- **默认（不指定档位）**：不额外指定服务速度，不自动升级收费档位；再次选默认可清除先前的速度设置。
- **Fast（快速）**：Chat Completions 和 Responses 均可使用，需服务方与模型支持。
- **Ultrafast（超快速）**：会自动开启 Responses，适用于 GPT-6 Astra / GPT-6.1 Sol 等支持模型。手动关闭 Responses 会同时清除 Ultrafast，避免开关与实际请求不一致。

这不是「思考强度」选项，两者互不替代。快速档位可能增加费用，请先确认供应商的权限、支持范围和价格。第三方中转不会因域名被禁止，但必须实际支持相应档位；服务方拒绝时直接显示错误，不自动改成其它速度。使用「完整 URL」时，选择 Ultrafast 后还需自己把地址改为该中转的 Responses 完整地址。

设置会随供应商保存并供使用该供应商的主任务和子任务共用。也可以通过配置工具调整供应商的速度档位（默认 / fast / ultrafast）；已有自定义请求头中的旧 Priority 别名会按 Fast 发送，未知供应商自定义档位保留，不会静默变成收费档位。

## 管理供应商列表

列表里每行显示名称、上游格式、模型数量和启用状态。

- 点一行进入编辑页。
- **左滑一行**露出红色删除按钮。
- **长按名称或上游格式信息区域上下拖动**可以调整顺序，重启后顺序保持。

## 多 Key 模式

::: tip 版本说明
多 Key 模式从 v1.11.0-dev 起提供。
:::

如果你手上有同一个服务的多个密钥，可以在编辑页的「选项」里打开**多 Key 模式**，密钥不可用时会自动切到下一个并**自动重发本次请求**，不需要手动重试。打开后原来的单 Key 输入框会隐藏，密钥统一在「多 Key 管理」页里维护（原本填的那个会自动成为列表第一项，不会丢）。

管理页里有三部分：

**Key 列表**：逐行增删，顺序就是优先级。展开某条 Key 后，其右侧的眼睛图标可以切换该条的明文/掩码显示。

**取用策略**：

- `顺序`：始终用靠前的 Key，不可用时才换下一个。
- `轮询`：新会话轮流从不同的 Key 起步，用来摊平多个 Key 的额度消耗。

为保护服务端 Prompt 缓存并维持会话连贯性，**单会话内默认保持同一个 Key**，只在当前 Key 被判定不可用时才换。

**失败切换**：

- `自动切换状态码`：命中这些 HTTP 状态码时立即切换并重发，默认 `401,402,403,429`；留空用默认值，也可以按需增删（逗号分隔）。
- `冷却时长`：被切走的 Key 隔多久重新参与候选，默认 5 分钟，也可以设成不冷却。

一次请求会依次尝试列表里的 Key，全部失败才报错（提示「N 个 Key 均失败」）。流式回复若已经开始输出内容，则不再重发（避免重复输出），只切换后续请求要用的 Key。触发切换时聊天区会出现「已切换到第 N/M 个 Key」提示。

鉴权失败、权限不足、触发限流、余额或额度用尽这些属于「密钥不可用」，会触发切换；服务器 5xx、请求超时、连不上网这些换 Key 也没用，仍走原有的网络重试。若所有 Key 都在冷却中，会直接报错让你稍后重试，不会拿已知不可用的 Key 硬打。

App 重启后会回到第一个 Key，冷却记录清空。

## 新版协议端点

编辑页的「选项」中支持切换到供应商的新版协议：
- **上游格式为 OpenAI 时**：可选开启「Response API (新版)」；
- **上游格式为 Gemini 时**：可选开启「Interactions API (新版)」。

默认保持关闭即可。请在确认所接入的官方直连或中转服务支持对应新协议时按需开启。

## 其它选项

::: v-pre
- **自定义请求头**：给这个供应商的所有请求追加自定义 Header，完全覆盖同名默认头（比如填 `User-Agent` 就替换掉默认 UA）。值里可以写 <code v-pre>{{SESSION_ID}}</code>（当前会话 id）和 <code v-pre>{{API_KEY}}</code>（本次实际取用的 Key）占位符，发送前自动替换。少数中转网关会校验特定 Header，需要时才填。
- **自定义面板脚本**：让供应商在输入框上方显示一个余额或用量卡片。脚本放在 `~/.aharou/scripts/` 下，支持 Python、Bash、Node，配好后可以在编辑页点「运行测试」看输出。脚本路径本身也可以通过**配置通道**读写（`providers.<id>.panel_script`），所以「换个面板脚本」可以让 AI 直接代改。卡片里的链接按钮（如「管理控制台」）点开后在**内置浏览器**里打开并自动弹出浏览器面板；写脚本时链接要指向人看的网页，不要填 API 地址。
- **脚本参数**：给面板脚本额外注入环境变量 `AICODE_KEY_<KEY>`。参数值里可以写 <code v-pre>{{PROVIDER_API_KEY}}</code>（当前 Key）、<code v-pre>{{BASE_URL}}</code>、<code v-pre>{{MODEL}}</code> 等占位符，运行脚本前自动替换。
- **备用模型（多级回退）**：可以加多条备用模型，按列表顺序往下轮询——本供应商因为额度耗尽、鉴权失效或限流而失败时，先切第 1 条把本轮请求重发；第 1 条也失败就切第 2 条，依此往下，链走完才报错、下一轮回到主供应商重新开始。每条备用都自选「供应商 + 模型」，所以可以同一供应商换模型，也可以换供应商；模型留空就用那个供应商自己的当前模型。顺序用条目右侧的上移 / 下移调整。切换只在「这一轮还没吐任何内容」时发生，不会把半截回复拼到另一个模型上。它也能通过**配置通道**读写（`providers.<id>.fallback_chain`，每行 `供应商 id|模型名`；旧的 `providers.<id>.fallback_provider_id` 与 `providers.<id>.fallback_model` 仍可读，但只镜像第一条）。
- **请求中间件**：用一段 JSON 配置模型名映射和请求参数覆盖。界面仍显示原模型名，实际请求可映射到供应商支持的模型。中间件不再追加系统提示；旧配置中的相关正文不会自动导入或生效，需在「配置 → 记忆库」中自行维护。提示内容只能来自原生记忆条目，参数覆盖不能改写消息、系统提示或工具定义。
:::

## 模型管理

切到编辑页的「模型」标签页。

**拉取模型**：点「拉取模型」向服务端同步可用列表，结果按品牌分组、可搜索。每个模型旁边有能力标签：`Image` 支持图片、`Tools` 支持工具调用、`↑` 后面是上下文长度、`↓` 后面是最大输出。拉取弹窗右上角提供「全部添加」按钮，点击并确认后可一键将拉取到的所有新模型批量加入当前供应商。

拉取成功后会自动对齐本地列表：远端已不存在的模型会直接从列表移除（拉取失败或返回空列表时不动本地列表）。如果你手动添加的模型恰好不在远端返回结果里，也会一并被移除，需要时重新添加即可；不想这样可以在[偏好设置](/guide/general)里关掉「拉取模型后自动移除无效模型」。

**批量操作与多选**：模型列表右上角提供「多选」按钮，点击即可进入批量选择模式：
- **全选 / 取消全选**：一键勾选所有模型或清空选择。
- **测试**：对选中的所有模型依次发起连通性测试。
- **批量删除**：二次确认后批量删除选中的模型。
- 点左上角返回箭头或按返回键即可退出多选模式。

**手动添加**：中转站的模型名有时拉不全，可以点 + 手动输入模型名添加。编辑弹窗里还能自定义这个模型的：

- **显示名**：给这个模型起个别名，列表里就显示这个名字（留空则显示原始模型名）。调用的还是原来那个模型，只是看着好认。
- **模型图标**：可以给它单独配一张图（从相册选，或填图片链接），不设置就按模型名自动匹配内置品牌图标。图标是本地文件，不包含在备份里。
- **上下文窗口**：影响什么时候触发上下文自动压缩。
- **输出窗口**：单次回复的最大长度。
- **价格**：输入 / 输出 / 缓存读取 / 缓存写入四档，单位是美元每百万 token，用于费用统计。
- **能力开关**：图片输入、图片输出、工具调用、推理能力。

窗口值需填写有效的正整数，也可留空使用自动识别值；填写 0、负数、小数或超出范围的整数时会显示提示，且无法保存。编辑已有模型时，只改价格不会改变原有的总上下文、输入和输出窗口。
你填的值优先级最高；没填的会回退到自动识别的结果，自定义设置始终对应当前供应商下的原模型名。自动识别优先匹配完整名称，再尝试忽略大小写；仍未找到时尝试去掉 `-thinking`、`-preview`、`-high` 这类常见后缀，并尝试去掉供应商品牌前缀。上述尝试均失败后，才去掉最后一个 `-` 及其后面的部分匹配一次，不会反复截短名称。

**思考强度**：输入框**下方**工具栏上的闪电图标按钮始终可用，图标旁会显示当前档位。可选档位**按当前模型的元数据过滤**，只在元数据未命中时才列出全部七档——`不思考` / `默认` / `低` / `中` / `高` / `极高` / `最高`：

- `不思考`：明确关闭思考，只对能表达该语义的模型生效（如 Anthropic 系），其余服务商等同跳过。
- `默认`：请求里完全不发档位参数，由服务商按自己的默认处理——拿不准选哪档时用它，不会因为档位不被支持而被拒。
- 其余档位原样发给服务商；服务商不认某个档位时请求可能被拒，换一档即可。

之所以不按模型自动筛选：模型支持哪些档位是服务商声明的，实际经常报不全（例如只报 `low` / `high` / `max`），筛完就选不到「中」这类中间档了。选择会按模型记住，下次新会话用这个模型时自动沿用；没调过的模型则以「默认」起步（新会话不再默认发 `medium`）。

**测试**：每个模型行都有「测试」按钮，发一次真实请求验证连通性，结果显示在行下方。

**排序**：长按模型名称区域上下拖动，可以调整模型顺序，保存后立即生效（模型选择弹窗与默认模型列表都按这个顺序展示），重启后顺序保持。

**删除**：模型行左滑删除。如果删掉的模型正在被使用（当前供应商的选中模型、新会话默认模型、识图 / 生图 / 压缩 / 标题模型，或者某个历史会话绑定的模型），相关选择会自动回退到默认模型，不会再拿已删除的模型发请求。


## 工作区模型顺序

在聊天输入栏点击「模型顺序」，添加模型 1、2、3 等候选，用「上移」「下移」调整顺序并保存。同一供应商的不同模型可以分别加入。

主对话先使用第一项；请求按照现有设置重试，仍失败且没有输出正文、思考、图片或工具调用时，才切换下一项。同一会话后续请求沿用可用候选，所有候选失败后停止并显示错误，不调用列表外的默认模型。保存后的配置从下一轮生效。清空候选列表可恢复原来的单模型选择。标题、压缩、识图等专用模型不使用这份列表。


### 账号地区、Loomy 与签到

添加账号型供应商时，明确选择 Trae / WorkBuddy 国内版或国际版。原有 Trae、WorkBuddy 条目继续是国内版，凭据不跨地区复用；切换账号服务前先退出原账号池。账号型页面只显示账号相关配置，不展示 Key、兼容地址、多 Key 或 OpenAI 专属参数；Key 型供应商原有配置保留。

Loomy 使用**用户主动导入自己的会话**，不是 OAuth。先在 <https://loomy.xunfei.cn/> 登录，再通过遮罩输入框提供自己的 session；提交后清空输入，模型目录校验成功后才加密保存，过期需要重新导入。模型发现、积分和对话直连 Loomy 服务。

在账号卡点「签到」，WorkBuddy 国内个人账号、Trae 国内账号使用供应商签到服务；小浣熊请求桌面每日领取设置并核对前后余额。重复、未知和不支持结果不伪造新增积分；国际、企业及 Loomy 不发送签到请求。取消或退出会使未完成的账号操作失效。

配置通道支持 `providers.<id>.account_service`（`trae`、`trae-global`、`workbuddy`、`workbuddy-global`、`raccoon`、`loomy`）、Loomy 敏感只写字段 `account_session`，以及 `account_check_in`（写入 `run`）。会话不保存在普通 API Key 字段，也不会由配置读取返回。配置导入会同步合并模型目录和默认选项，无需再打开编辑页刷新。

### 多账号与无痕登录

账号供应商页面可连续添加多个账号。Android 9 及以上使用「添加账号（无痕）」的独立登录进程与专用网页目录，每次登录清理该专用目录的会话，不清除应用内其它网页的登录状态；这只隔离本地会话，不代表对供应商匿名。Android 8 保留「添加账号（兼容登录）」，不承诺会话隔离，换账号时需在授权网页自行退出原网站账号。Loomy 通过用户主动导入自己的会话追加账号，不提供虚构的 OAuth 登录。

账号按首次登录顺序保存；能可靠识别的同一远端用户重新登录更新原位置，否则新增一个账号。目录为全池成功结果按首次出现顺序合并去重；部分账号失败会提示，全部失败不会用空结果覆盖模型。请求仅选择支持所选模型的账号，默认最早可用，认证、额度、封禁、限流或连接建立失败才尝试下一位；参数错误不切换，开始流输出后不重放。每个账号可单独签到和退出，「退出登录」仍退出整个池。

配置通道的 `provider_accounts` 集合与界面使用同一账号池：列出的是不含凭据的本地账号标识及顺序，`remove` 仅退出对应账号。Loomy 可通过 `add` 提供 `provider_id` 与敏感字段 `apiKey`（值为用户自己的 session，该名称沿用已有脱敏键）；其余供应商需在界面由用户主动完成网页登录。现有 `account_session` 也会追加 Loomy 账号，不覆盖已有账号。此处不承诺完整应用备份已包含账号凭据。


## 每个供应商的输入语言

在供应商表单中选择「原文」「中文」或「英文」。默认「原文」兼容原有配置；关闭离线翻译总开关后所有供应商保持原文。选择中文或英文时，系统提示与用户、助手历史正文中的自然语言在本机转换后发送，顺序和图片附件不变。代码块、路径、占位符、凭据、工具参数、工具结果、思考与签名不做转换。

先到设置中的「离线中英翻译」，分别下载并校验英译中和中译英语言包。未安装、正在检查、下载中或推理失败时，会保留原文，并在该页显示对应方向状态；不会调用外部翻译服务。英文输入需要中译英包，中文输入和回复中文显示需要英译中包。

回复的中文译文仅用于现有正文显示层，原始模型回复与工具、思考签名继续保存并回传，不以译文覆盖原文。译文仅供参考，长代码与结构化内容不会逐字推理。

添加供应商的预设选择器现在使用居中对话框，可滚动选择，不支持下滑关闭；通过「关闭」或系统返回键退出，编辑表单保持原有页面布局。


### Dumate website account (pending owner validation)

Dumate (`dumate`) uses the user's normal PC website login at <https://dumate.baidu.com/app> in the existing isolated account-login process (Android 9+). It is not OAuth and does not scan other apps. After logging in, explicitly press “我已完成网页登录”. Only that action sends the website cookie through the private Binder result receiver to the pending login in the main process. Origin, login identity, nonce, revision and expiry are checked before exchanging the cookie for a short-lived gateway ticket. The account is appended to the existing encrypted ordered account pool only after a nonempty gateway model catalog is validated.

The website cookie is sent only to the fixed official website token and user-info routes. Gateway Bearer tickets are sent only to the fixed `dumate-svc.baidu.com` model/chat routes. Models come from the gateway directory, not a preset list. Expired tickets refresh using the website cookie; an expired cookie requires the user to log in again, never automatic login replay. The gateway retains native OpenAI JSON/SSE and tools. Stable account identity comes from the PC website `user/info` field `bceUserId`, never an inferred name.

Balance is unsupported (`null`, with an explicit UI explanation); this integration does not request check-in, tasks or rewards. It does not use a new server or external proxy. Credentials are stored only in the existing encrypted account vault, not in API-key settings or URLs. The account service is selectable through the existing `providers.<id>.account_service` configuration route; login still requires the explicit website UI action. No owner account has been used for upstream validation: the UI intentionally says “网页登录待本人验证”.


### Doubao website session (text only; owner verification pending)

Doubao (`doubao`) opens <https://www.doubao.com/chat/> in the existing isolated Android 9+ login process. Complete the normal official login and press “我已完成网页登录”. The private ResultReceiver Bundle carries the session only to its matching pending provider kind, origin, nonce, login ID, revision and expiry. The cookie is not placed in a URL, API key, log or app-created plaintext file. The existing encrypted Vault and ordered account pool are reused. A nonvisitor `/passport/account/info/v2/` identity (`data.user_id_str` or `data.user_id`) and a nonempty live chat-model directory must both validate before appending the account.

Models are discovered from the service's live catalog, with an account-bound official webpage directory used when the old switch configuration is absent. Chat entries retain their original IDs and use `agent_mode=2`. Work inference entries have distinct `work:` IDs and a `Work` name suffix; requests retain the service's native model key and declared Work mode ID with `agent_mode=1` and cloud runtime. Disabled models, disabled modes and upgrade-required entries are not exposed. Mode-owned lists are not expanded with unrelated global models. No production model IDs or entitlements are invented. The bridge supports plain text and thinking text; local tasks, tools and task handoffs remain unsupported and return an explicit failure rather than a completed answer. Ordered system/developer/user/assistant text history is sent as an explicitly labeled, size-limited JSON transcript in one native text message; this is **not native role/session continuity**. Each request creates a website conversation, which is **not automatically deleted**. This can leave conversations in the user's official website account.

Tools, tool results, structured tool history, images, custom temperature/token limits and unverified generation overrides fail explicitly; do not select this provider for tool-driven agent/project execution. No tool calls or usage/token counts are fabricated. Native SSE_ACK, STREAM_CHUNK text/thinking patches and SSE_REPLY_END with end_type=3 drive both OpenAI-compatible JSON and SSE output. Truncation, stream errors, unsupported task handoffs and cancellation cannot produce a successful finish. Cookie-bearing requests are fixed to the official `www.doubao.com` identity/catalog/chat routes; redirects and 401/403 challenges require normal website login again without automated bypass or login replay. Locally generated numeric device metadata persists with the encrypted account credential, rather than copying another user's device ID. Balance, check-in and rewards are unsupported.

This integration follows the frozen public Fullstop000/doubao-cli reference at `62dd0ac1` for HTTP, catalog, native request and SSE fields, not an assumed OpenAI endpoint. Real-account login/chat and Android device acceptance remain pending owner verification. HTTP fixtures are included; their presence alone does not establish runtime success.


### 百度搭子与豆包网页登录

百度搭子使用官方 PC 入口 `https://dumate.baidu.com/app`。旧版搭子网址保存的登录态不能跨网站使用，更新后请在官方页面重新登录；不会把旧 Cookie 发送到新网站。豆包完成登录后可以停留在具体会话页，再点完成登录。登录窗口中的密码和验证码仅由本人在官方页面输入；完成后仍须通过服务方账号和模型目录校验，校验失败不会新增账号。

Trae 的「登录并打开 TRAE」授权回调由当前应用登录窗口接收，不需要启动外部 IDE。支持当前官方页面返回的授权码和登录凭据；凭据回调在核对本次登录后完成换票与账号身份验证，再保存账号。取消或过期后请重新发起登录，不重复使用旧授权回调。本人账号的实际登录与聊天仍待验证。

豆包校验失败时，窗口会显示失败发生在账号身份、模型目录还是网络请求，并附脱敏错误码。401 表示登录态未被服务方接受，403 表示服务方拒绝请求；请按网页提示正常登录或处理限制后重试。错误提示不会包含 Cookie 或服务方返回的原始私密正文，失败不会新增账号。

如果服务方接口不再提供原来的模型切换配置，应用会尝试读取同一官方登录网页返回的目录。网页中的账号必须与本次登录身份一致，匿名页面、游客、其他账号或无法识别的页面都不会保存。普通聊天模型与 Work 推理模型分开标识：Work 项名称带「Work」，模型标识以 `work:` 开头，按服务方真实模式发送请求。停用、需要升级或不属于所选模式的条目仍会排除；本地任务、工具和任务交接不支持，不会伪装成已完成。不会使用应用内置的默认模型来填补空目录。验证后仍没有可用模型时，请保留失败提示并反馈，不要提供 Cookie、密码或验证码。


### Our Free Model（公开匿名免费模型）

在添加供应商面板选择「Our Free Model（公开免费模型）」，点击「连接免费模型」，无需填写账号或密钥。连接时从 Kilo 公开服务读取目录，仅保留服务方明确标记免费的模型；连接成功后可选择模型，刷新可更新目录。免费模型下架、服务拒绝或限流时会显示真实失败，不使用静态免费清单，也不切换到付费模型。重复连接更新同一本地连接，退出可移除连接。

请求直接发送至 Kilo 的公开 OpenAI 兼容服务，使用应用已有对话和流式工具协议，不安装 Node 或启动代理服务。免费服务可能记录提示内容并用于改进模型，请勿发送敏感信息。该能力参考 MIT 授权的 dsh-our-free-model，许可证随应用保留；目前接入 Kilo，Zen 所需的客户端指纹及伪装会话未移植。目录可达和真实推理可用是不同条件，不能据目录成功保证模型始终可用。

本地连接使用已有 `provider_accounts` 账号池保存、列出和移除；配置通道可通过 `provider_accounts` 免密添加连接，与页面使用同一连接逻辑。协议夹具覆盖目录准入、空白保真与失败不重试，实际设备使用仍待验证。

### DeepSeek 网页账号（文本模式）

在添加供应商面板选择「DeepSeek 网页账号」，在隔离登录窗口内由本人完成官方 `https://chat.deepseek.com/sign_in` 登录，再点「完成登录」。应用读取当前页面的网页登录凭据并校验真实用户身份；没有稳定用户身份不会保存账号。只有本机保存成功才关闭窗口，取消后不会接受迟到结果。网页凭据仅进入独立加密账号池，不作为开放平台 API Key 使用。

当前适配为网页文本模式，仅列出网页默认模型，不提供虚构的多模型切换。不支持启动新的工具调用、附件、余额或签到；已有工具调用记录会作为普通文本历史传入，不会在网页端重放。需要 Agent 工具能力时请使用已支持工具调用的 API 供应商。遇到登录过期请重新登录；服务方拒绝原生请求时显示失败，不另开常驻浏览器继续请求。异常断流会报错，不显示为正常完成。

当前提供受控协议测试源码，尚未用真实 DeepSeek 账号验证登录和回复，也未验证网页模式的线上可用性。

### 码道 CodeArts 网页账号（文本模式，协议逆向）

在添加供应商面板选择「码道 CodeArts」，在隔离登录窗口内由本人完成华为云登录，再点「完成登录」。应用读取当前官方站点的登录会话，校验服务方返回的账号身份与模型目录后保存账号。同一账号重新登录会更新原有槽位；运行中服务方轮换且返回新的 Cookie 时会保存更新值。网页文本模式不支持工具、附件、余额或签到；会话过期且服务方不再提供有效 Cookie 时仍需本人重新登录。接口协议基于公开页面逆向，未用真实华为云账号验证，UI 明确标注「网页登录待本人验证」。


### 果办（华为云个人账号）

选择果办账号供应商后，添加账号会打开华为云官方授权页。使用本人的个人账号完成授权并返回应用，应用会校验账号权限，再读取账号可用的模型目录。无需手填密钥，也不会读取电脑端果办的凭据。多个账号沿用账号池的添加顺序。

此入口仅支持个人账号；企业账号、邀请码激活与购买订阅请使用官方客户端。账号无权限、模型目录为空或服务不可达时显示真实失败，不生成可用模型。余额暂不支持；真实账号授权与对话仍需真机验证。
