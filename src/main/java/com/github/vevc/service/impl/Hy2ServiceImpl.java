package com.github.vevc.service.impl;

import com.github.vevc.config.AppConfig;
import com.github.vevc.service.AbstractAppService;
import com.github.vevc.util.LogUtil;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * @author vevc
 */
public class Hy2ServiceImpl extends AbstractAppService {

    private static final String APP_NAME = "sh";
    private static final String APP_CONFIG_NAME = "config.yaml";
    private static final String APP_STARTUP_NAME = "startup.sh";
    private static final String APP_DOWNLOAD_URL = "https://github.com/apernet/hysteria/releases/download/app/v%s/hysteria-linux-%s";
    // Hy2 URI format: hy2://password@host:port?insecure=1&sni=domain#remarks
    private static final String SHARE_URL = "hy2://%s@%s:%s?insecure=1&sni=%s#%s";

    @Override
    protected String getAppDownloadUrl(String appVersion) {
        String arch = OS_IS_ARM ? "arm64" : "amd64";
        return String.format(APP_DOWNLOAD_URL, appVersion, arch);
    }

    @Override
    public void install(AppConfig appConfig) throws Exception {
        File workDir = this.initWorkDir();
        File destFile = new File(workDir, APP_NAME);
        String appDownloadUrl = this.getAppDownloadUrl(appConfig.getAppVersion());
        LogUtil.info("Hy2 server download url: " + appDownloadUrl);
        this.download(appDownloadUrl, destFile);
        LogUtil.info("Hy2 server downloaded successfully");
        this.setExecutePermission(destFile.toPath());
        LogUtil.info("Hy2 server installed successfully");

        // generate cert
        LogUtil.info("Generating self-signed certificate...");
        this.generateCert(workDir, appConfig.getDomain());

        // write config
        this.writeConfig(workDir, appConfig);
        LogUtil.info("Hy2 server config created successfully");

        // add startup.sh
        // Usage: ./sh server -c config.yaml
        String startupScript = String.format(
                "#!/usr/bin/env sh\n\nexport PATH=%s\nexec sh -c 'sh server -c %s'", 
                workDir.getAbsolutePath(), APP_CONFIG_NAME);
        // Note: 'sh' in the exec command refers to the binary we renamed to 'sh'. 
        // We use 'sh -c' (shell) to execute the command string which calls our binary 'sh'.
        // Wait, if PATH includes workDir, calling 'sh' might still call system /bin/sh.
        // Better to use ./sh
        startupScript = String.format(
                "#!/usr/bin/env sh\n\ncd %s\nexec ./sh server -c %s", 
                workDir.getAbsolutePath(), APP_CONFIG_NAME);
        
        Files.writeString(new File(workDir, APP_STARTUP_NAME).toPath(), startupScript);

        // update sub file
        this.updateSubFile(appConfig);
    }

    private void generateCert(File workDir, String domain) {
        try {
            // openssl req -x509 -nodes -newkey rsa:2048 -keyout key.key -out cert.crt -days 3650 -subj "/CN=domain"
            ProcessBuilder pb = new ProcessBuilder(
                    "openssl", "req", "-x509", "-nodes", "-newkey", "rsa:2048",
                    "-keyout", "key.key", "-out", "cert.crt",
                    "-days", "3650", "-subj", "/CN=" + domain
            );
            pb.directory(workDir);
            pb.redirectOutput(new File("/dev/null"));
            pb.redirectError(new File("/dev/null"));
            int code = pb.start().waitFor();
            if (code != 0) {
                LogUtil.info("OpenSSL cert generation failed with code: " + code + ". Ignoring if using pre-existing certs, but this is likely an error.");
            }
        } catch (Exception e) {
            LogUtil.error("Failed to generate certificate", e);
        }
    }

    private void writeConfig(File configPath, AppConfig appConfig) throws Exception {
        String yaml = "listen: :" + appConfig.getPort() + "\n" +
                      "tls:\n" +
                      "  cert: cert.crt\n" +
                      "  key: key.key\n" +
                      "auth:\n" +
                      "  type: password\n" +
                      "  password: " + appConfig.getPassword() + "\n" +
                      "ignoreClientBandwidth: true\n";
        
        File configFile = new File(configPath, APP_CONFIG_NAME);
        Files.writeString(configFile.toPath(), yaml,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private void updateSubFile(AppConfig appConfig) throws Exception {
        // hy2://password@host:port?insecure=1&sni=domain#remarks
        String shareLink = String.format(SHARE_URL, 
                appConfig.getPassword(),
                appConfig.getDomain(), 
                appConfig.getPort(), 
                appConfig.getDomain(), 
                appConfig.getRemarksPrefix());
        
        LogUtil.info("Hy2 Share Link: " + shareLink);
        
        String base64Url = Base64.getEncoder().encodeToString(shareLink.getBytes(StandardCharsets.UTF_8));
        Path nodeFilePath = new File(this.getWorkDir(), appConfig.getUuid()).toPath();
        Files.write(nodeFilePath, Collections.singleton(base64Url));
    }

    @Override
    public void startup() {
        File workDir = this.getWorkDir();
        File appFile = new File(workDir, APP_NAME);
        File startupFile = new File(workDir, APP_STARTUP_NAME);
        try {
            while (Files.exists(appFile.toPath())) {
                ProcessBuilder pb = new ProcessBuilder("sh", startupFile.getAbsolutePath());
                pb.directory(workDir);
                pb.redirectOutput(new File("/dev/null"));
                pb.redirectError(new File("/dev/null"));
                LogUtil.info("Starting Hy2 server...");
                int exitCode = this.startProcess(pb);
                if (exitCode == 0) {
                    LogUtil.info("Hy2 server process exited with code: " + exitCode);
                    break;
                } else {
                    LogUtil.info("Hy2 server process exited with code: " + exitCode + ", restarting...");
                    TimeUnit.SECONDS.sleep(3);
                }
            }
        } catch (Exception e) {
            LogUtil.error("Hy2 server startup failed", e);
        }
    }

    @Override
    public void clean() {
        File workDir = this.getWorkDir();
        File appFile = new File(workDir, APP_NAME);
        File configFile = new File(workDir, APP_CONFIG_NAME);
        File startupFile = new File(workDir, APP_STARTUP_NAME);
        File keyFile = new File(workDir, "key.key");
        File certFile = new File(workDir, "cert.crt");
        try {
            TimeUnit.SECONDS.sleep(30);
            Files.deleteIfExists(appFile.toPath());
            Files.deleteIfExists(configFile.toPath());
            Files.deleteIfExists(startupFile.toPath());
            // Files.deleteIfExists(keyFile.toPath()); // Maybe keep certs to avoid regeneration? 
            // But if we delete the binary, we're basically hiding usage.
            // Let's delete config and startup script, maybe keep certs?
            // User didn't specify. I'll delete them to be clean.
            Files.deleteIfExists(keyFile.toPath());
            Files.deleteIfExists(certFile.toPath());
        } catch (Exception e) {
            LogUtil.error("Hy2 server cleanup failed", e);
        }
    }
}
