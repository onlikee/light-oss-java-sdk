# 发布到 Maven Central

本项目发布坐标为：

```text
com.onlikee:light-oss-sdk:0.1.0
```

普通构建不会签名或上传。只有显式启用 `central-release` Profile 才会生成源码与 Javadoc JAR、使用 Bouncy Castle 纯 Java signer 签名，并调用 Maven Central Publisher Portal。该 Profile 保留 `deploy` 生命周期阶段以触发 Central Publisher，同时跳过传统 `maven-deploy-plugin`，因此不需要配置 `<distributionManagement>`。

## 首次发布前准备

1. 确认 `com.onlikee` 已在 [Central Publisher Portal](https://central.sonatype.com/) 验证。
2. 准备并妥善保管受口令保护的 OpenPGP 私钥。BC signer 不使用 `gpg.exe` 或 GnuPG 主目录；它从环境变量 `MAVEN_GPG_KEY` 读取 TSK 格式私钥，从 `MAVEN_GPG_PASSPHRASE` 读取口令。可选的 `MAVEN_GPG_KEY_FINGERPRINT` 用于在私钥中有多个密钥时指定签名密钥。
3. 使用任意兼容的 OpenPGP 工具将签名主密钥的公钥发布到 Central 支持的密钥服务器。GnuPG 可用于密钥生成或公钥发布，但不是本项目构建的运行前提。
4. 在 Central Portal 的 Account 页面生成 User Token，并仅在本机 `%USERPROFILE%\.m2\settings.xml` 配置它：

   ```xml
   <settings>
     <servers>
       <server>
         <id>central</id>
         <username>CENTRAL_TOKEN_USERNAME</username>
         <password>CENTRAL_TOKEN_PASSWORD</password>
       </server>
     </servers>
   </settings>
   ```

不要把 Token、OpenPGP 私钥、口令或本机 `settings.xml` 提交到仓库。通过当前会话环境变量或受控的密钥管理工具提供签名密钥和口令。

## 本地验证

普通开发构建：

```powershell
mvn.cmd -B clean verify
```

配置签名密钥环境变量后，验证发布制品和签名：

```powershell
mvn.cmd -B -Pcentral-release clean verify
```

`target/` 中应包含主 JAR、`-sources.jar`、`-javadoc.jar`，以及它们和 POM 对应的 `.asc` 签名文件。

## 上传与人工发布

在 SDK 功能完成、测试通过且确认版本 `0.1.0` 从未发布后，执行：

```powershell
mvn.cmd -B -Pcentral-release clean deploy
```

该命令上传发布包并等待 Central 校验为 `VALIDATED`。`deploy` 阶段只用于触发 Central Publisher，传统 `maven-deploy-plugin` 已被跳过。配置明确禁用了自动发布；请登录 [Central Publisher Portal 的 Deployments 页面](https://central.sonatype.com/publishing/deployments) 检查制品后，手动点击 **Publish**。

Maven Central 已发布的版本不能覆盖或删除。下一次发布前，先将 `pom.xml` 中的版本改为新的未发布版本。
