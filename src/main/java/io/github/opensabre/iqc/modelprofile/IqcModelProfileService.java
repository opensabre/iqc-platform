package io.github.opensabre.iqc.modelprofile;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.governance.IqcModelProvider;
import io.github.opensabre.iqc.modelprofile.dao.IqcModelProfileMapper;
import io.github.opensabre.iqc.modelprofile.model.IqcModelProfile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.jasypt.encryption.StringEncryptor;

import java.net.URI;
import java.util.Arrays;
import java.util.List;

/** Manages model profiles while keeping provider credentials outside IQC data. */
@Service
public class IqcModelProfileService {
    private final IqcModelProfileMapper mapper;
    private final StringEncryptor encryptor;
    public IqcModelProfileService(IqcModelProfileMapper mapper, StringEncryptor encryptor) { this.mapper = mapper; this.encryptor = encryptor; }
    public List<IqcModelProfile> list() { return mapper.selectList(Wrappers.<IqcModelProfile>lambdaQuery().orderByAsc(IqcModelProfile::getStatus).orderByDesc(IqcModelProfile::getCreatedTime)); }

    /** Creates an enabled model profile after provider and connection validation. */
    @Transactional
    public IqcModelProfile create(ModelCommand command) {
        validate(command);
        if (findByCode(command.code()) != null) throw IqcException.invalidState("模型配置编码已存在: " + command.code());
        IqcModelProfile profile = new IqcModelProfile(); apply(profile, command); profile.setStatus("ENABLED"); profile.setVersionNo(1); mapper.insert(profile); return profile;
    }
    /** Updates model parameters but keeps the stable profile code. */
    @Transactional
    public IqcModelProfile update(String id, ModelCommand command) {
        IqcModelProfile profile = require(id);
        if (blank(command.secretRef())) command = command.withSecretRef(profile.getSecretRef());
        command = command.withCode(profile.getCode()); validate(command); apply(profile, command);
        if(profile.getVersionNo()==null || profile.getVersionNo()<1 || profile.getVersionNo()==Integer.MAX_VALUE)throw IqcException.invalidState("模型版本无效或已耗尽");
        profile.setVersionNo(profile.getVersionNo() + 1); if(mapper.updateById(profile)!=1)throw IqcException.invalidState("更新模型失败"); return profile;
    }
    @Transactional
    public IqcModelProfile setEnabled(String id, boolean enabled) { IqcModelProfile profile = require(id); profile.setStatus(enabled ? "ENABLED" : "DISABLED"); mapper.updateById(profile); return profile; }

    private void validate(ModelCommand value) {
        if (blank(value.name()) || blank(value.code()) || blank(value.provider()) || blank(value.modelName())) throw IqcException.invalidArgument("模型配置名称、编码、供应商和模型名称不能为空");
        if (!value.code().matches("[A-Z][A-Z0-9_]{1,63}")) throw IqcException.invalidArgument("模型配置编码必须为大写字母、数字或下划线");
        if (Arrays.stream(IqcModelProvider.values()).noneMatch(item -> item.value().equals(value.provider()))) throw IqcException.invalidArgument("不支持的模型供应商");
        if (!"OLLAMA".equals(value.provider()) && blank(value.secretRef())) throw IqcException.invalidArgument("非本地模型必须填写 API Key");
        if (!blank(value.endpoint())) try { URI uri = URI.create(value.endpoint()); if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null) throw new IllegalArgumentException(); } catch (Exception exception) { throw IqcException.invalidArgument("模型 Endpoint 必须是有效的 HTTP(S) URL"); }
        if (value.temperature() == null || value.temperature() < 0 || value.temperature() > 2) throw IqcException.invalidArgument("模型温度必须在 0 到 2 之间");
        if (value.timeoutSeconds() == null || value.timeoutSeconds() < 1 || value.timeoutSeconds() > 600) throw IqcException.invalidArgument("模型超时时间必须在 1 到 600 秒之间");
        if(value.maxOutputTokens()!=null && !value.maxOutputTokens().isNull()){
            var limit=outputLimit(value.maxOutputTokens());
            if(limit!=null && !java.util.Set.of("MAX_TOKENS","MAX_COMPLETION_TOKENS").contains(value.outputTokenParameter()==null?"":value.outputTokenParameter()))
                throw IqcException.invalidArgument("请选择输出Token参数类型");
        }else if(value.outputTokenParameter()!=null)throw IqcException.invalidArgument("修改输出参数必须同时提供上限");
        if (value.maxRetries() == null || value.maxRetries() < 0 || value.maxRetries() > 5) throw IqcException.invalidArgument("模型重试次数必须在 0 到 5 之间");
    }
    private static Integer outputLimit(com.fasterxml.jackson.databind.JsonNode value){
        if(value==null || value.isNull())return null;
        if(!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue()<0 || value.intValue()>1000000)
            throw IqcException.invalidArgument("输出Token上限必须为0到1000000的整数");
        return value.intValue()==0?null:value.intValue();
    }
    private IqcModelProfile findByCode(String code) { return mapper.selectOne(Wrappers.<IqcModelProfile>lambdaQuery().eq(IqcModelProfile::getCode, code)); }
    private IqcModelProfile require(String id) { IqcModelProfile profile = mapper.selectOne(Wrappers.<IqcModelProfile>lambdaQuery().eq(IqcModelProfile::getId,id).last("FOR UPDATE")); if (profile == null) throw IqcException.notFound("模型配置不存在: " + id); return profile; }
    private void apply(IqcModelProfile target, ModelCommand source) { target.setName(source.name().trim()); target.setCode(source.code().trim()); target.setDescription(source.description()); target.setProvider(source.provider()); target.setModelName(source.modelName().trim()); target.setEndpoint(source.endpoint()); target.setSecretRef(encryptSecret(source.secretRef())); target.setTemperature(source.temperature()); target.setTimeoutSeconds(source.timeoutSeconds()); target.setMaxRetries(source.maxRetries()); if(source.maxOutputTokens()!=null && !source.maxOutputTokens().isNull()){target.setMaxOutputTokens(outputLimit(source.maxOutputTokens()));target.setOutputTokenParameter(target.getMaxOutputTokens()==null?null:source.outputTokenParameter());} if(source.pricingJson()!=null)target.setPricingJson(ModelPricing.canonical(source.pricingJson())); }
    private String encryptSecret(String value) { if (blank(value) || value.startsWith("env:") || value.startsWith("ENC(")) return value; return "ENC(" + encryptor.encrypt(value) + ")"; }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    public record ModelCommand(String name, String code, String description, String provider, String modelName,
                               String endpoint, String secretRef, Double temperature, Integer timeoutSeconds, Integer maxRetries,String pricingJson,com.fasterxml.jackson.databind.JsonNode maxOutputTokens,String outputTokenParameter) {
        public ModelCommand(String name,String code,String description,String provider,String modelName,String endpoint,String secretRef,
                Double temperature,Integer timeoutSeconds,Integer maxRetries){this(name,code,description,provider,modelName,endpoint,secretRef,temperature,timeoutSeconds,maxRetries,null,null,null);}
        public ModelCommand(String name,String code,String description,String provider,String modelName,String endpoint,String secretRef,
                Double temperature,Integer timeoutSeconds,Integer maxRetries,String pricingJson){this(name,code,description,provider,modelName,endpoint,secretRef,temperature,timeoutSeconds,maxRetries,pricingJson,null,null);}
        ModelCommand withCode(String stableCode) { return new ModelCommand(name, stableCode, description, provider, modelName, endpoint, secretRef, temperature, timeoutSeconds, maxRetries,pricingJson,maxOutputTokens,outputTokenParameter); }
        ModelCommand withSecretRef(String value) { return new ModelCommand(name, code, description, provider, modelName, endpoint, value, temperature, timeoutSeconds, maxRetries,pricingJson,maxOutputTokens,outputTokenParameter); }
    }
}
