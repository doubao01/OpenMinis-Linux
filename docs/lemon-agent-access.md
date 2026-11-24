# 内置柠檬工具的 Agent 接入

启用「设置 → MCP 集成 → 内置 MCP Server」后，App 的原生 `mcp` 工具会自动发现内置目录。无需另填回环 URL 或复制访问令牌，也无需打开工具目录面板。

## 服务器发现

- 默认运行期条目为 `minis_builtin`，只向 Agent 提供 `lemon_list` 和 `lemon_call`。
- 条目由内置服务的启用状态与配置端口派生，不写入 `servers.json`。手动配置、外部服务器和原有凭据保持原样。
- 已有配置指向该内置回环服务时复用原条目，避免重复。名称已被外部服务器占用时，运行期条目使用 `_2`、`_3` 后缀；以 `mcp(action="servers")` 返回的名称为准。
- 原生连接跟随服务实际绑定端口，并在每次请求时读取当前 Bearer Token。运行期条目仅用于原生 `mcp`，不是 `minis-mcp-cli` 的持久配置。
- 聊天中的 MCP 开关可单独关闭本会话的目录访问；关闭全局内置服务后，会话开关不会自行启动服务。
- 会话开关与单个工具开关使用稳定的内置服务身份，名称冲突导致的运行期别名变化不会重新放行已关闭的访问。

## 按需调用

模型先发现目录接口，再搜索所需能力，读取参数说明并调用工具。以下使用默认运行期名称：

```json
{"action":"tools","server":"minis_builtin"}
```

```json
{"action":"call","server":"minis_builtin","tool":"lemon_list","arguments":{"keyword":"appops_get","limit":10}}
```

```json
{"action":"call","server":"minis_builtin","tool":"lemon_call","arguments":{"name":"appops_get","args":{"pkg":"com.openminis.linux"}}}
```

`lemon_list` 返回名称、说明、领域、只读标记、`params` 和 `required`。`lemon_call` 使用这些参数执行目录命令；生成目录中的逗号分隔必填项与旧版竖线分隔格式均可识别。

## 开关与执行结果

工具管理页面使用原生连接读取内置目录接口，保留 `lemon_list` / `lemon_call` 的单独开关。自动接入条目不会额外开放内置服务器的 shell、文件写入或应用管理接口。

调用仍经过会话权限、工具开关与内置服务的 Shell 策略，执行沿用现有权限判断与审计。目录数量不等于设备可执行数量：需要 Root、Termux、厂商节点或上游脚本的工具，仍取决于设备运行环境。

## 验证

单测覆盖运行期发现、名称冲突、已有配置复用、端口跟随、会话开关、提示词更新、Token 不落盘、目录接口限制和参数说明。编译与单测由 GitHub Actions 的 `PR Checks` 执行，安装包由 `Android APK` 工作流生成；设备验收另行核对实际安装提交与调用结果。
