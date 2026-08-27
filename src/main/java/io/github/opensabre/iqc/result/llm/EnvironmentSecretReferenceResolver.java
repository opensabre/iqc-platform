package io.github.opensabre.iqc.result.llm;

import org.springframework.core.env.Environment;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.jasypt.encryption.StringEncryptor;

/** Resolves the minimal `env:NAME` reference format from the deployment environment. */
@Component
public class EnvironmentSecretReferenceResolver implements SecretReferenceResolver {
    private final Environment environment;
    private final StringEncryptor encryptor;
    public EnvironmentSecretReferenceResolver(Environment environment) { this(environment, null); }
    @Autowired
    public EnvironmentSecretReferenceResolver(Environment environment, StringEncryptor encryptor) { this.environment = environment; this.encryptor = encryptor; }
    @Override public String resolve(String reference) {
        if (reference != null && reference.startsWith("ENC(")) {
            if (encryptor == null) throw new IllegalStateException("加密密钥解密器未配置");
            return encryptor.decrypt(reference.substring(4, reference.length() - 1));
        }
        if (reference == null || !reference.matches("env:[A-Z][A-Z0-9_]{1,127}"))
            throw new IllegalArgumentException("密钥配置格式无效，仅支持 env:NAME 或 ENC(...) 引用");
        String value = environment.getProperty(reference.substring(4));
        if (value == null || value.isBlank()) throw new IllegalStateException("模型密钥引用未配置");
        return value;
    }
}
