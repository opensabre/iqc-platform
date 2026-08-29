package io.github.opensabre.iqc.skill;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.skill.dao.IqcSkillMapper;
import io.github.opensabre.iqc.skill.dao.IqcSkillVersionMapper;
import io.github.opensabre.iqc.skill.model.IqcSkill;
import io.github.opensabre.iqc.skill.model.IqcSkillVersion;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Manages reusable, credential-free Skill assets. */
@Service
public class IqcSkillService {
    private final IqcSkillMapper mapper;
    private final ObjectMapper objectMapper;
    private final IqcSkillVersionMapper versionMapper;

    public IqcSkillService(IqcSkillMapper mapper, ObjectMapper objectMapper, IqcSkillVersionMapper versionMapper) {
        this.mapper = mapper; this.objectMapper = objectMapper; this.versionMapper = versionMapper;
    }

    /** Lists Skills with enabled items first and newest items first. */
    public List<IqcSkill> list() {
        return mapper.selectList(Wrappers.<IqcSkill>lambdaQuery()
                .orderByAsc(IqcSkill::getStatus).orderByDesc(IqcSkill::getCreatedTime));
    }

    /** Creates an enabled Skill after validating its stable code and JSON schemas. */
    @Transactional
    public IqcSkill create(String name, String code, String description, String instructions,
                           String inputSchemaJson, String outputSchemaJson) {
        validate(name, code, instructions, inputSchemaJson, outputSchemaJson);
        if (findByCode(code) != null) throw IqcException.invalidState("Skill 编码已存在: " + code);
        IqcSkill skill = new IqcSkill();
        apply(skill, name, code, description, instructions, inputSchemaJson, outputSchemaJson);
        skill.setStatus("ENABLED"); skill.setVersionNo(1); mapper.insert(skill); snapshot(skill);
        return skill;
    }

    /** Updates a Skill and increments its configuration version. */
    @Transactional
    public IqcSkill update(String id, String name, String description, String instructions,
                           String inputSchemaJson, String outputSchemaJson) {
        IqcSkill skill = require(id);
        validate(name, skill.getCode(), instructions, inputSchemaJson, outputSchemaJson);
        apply(skill, name, skill.getCode(), description, instructions, inputSchemaJson, outputSchemaJson);
        skill.setVersionNo(skill.getVersionNo() + 1); mapper.updateById(skill); snapshot(skill);
        return skill;
    }

    /** Changes availability without deleting historical references. */
    @Transactional
    public IqcSkill setEnabled(String id, boolean enabled) {
        IqcSkill skill = require(id);
        String status = enabled ? "ENABLED" : "DISABLED";
        if (status.equals(skill.getStatus())) return skill;
        skill.setStatus(status); skill.setVersionNo(skill.getVersionNo() + 1); mapper.updateById(skill); snapshot(skill); return skill;
    }

    public List<IqcSkillVersion> versions(String id) {
        require(id);
        return versionMapper.selectList(Wrappers.<IqcSkillVersion>lambdaQuery()
                .eq(IqcSkillVersion::getSkillId, id).orderByDesc(IqcSkillVersion::getVersionNo));
    }

    @Transactional
    public IqcSkill rollback(String id, int versionNo) {
        IqcSkill skill = require(id);
        IqcSkillVersion version = versionMapper.selectOne(Wrappers.<IqcSkillVersion>lambdaQuery()
                .eq(IqcSkillVersion::getSkillId, id).eq(IqcSkillVersion::getVersionNo, versionNo));
        if (version == null) throw IqcException.notFound("Skill 版本不存在: " + versionNo);
        apply(skill, version.getName(), skill.getCode(), version.getDescription(), version.getInstructions(),
                version.getInputSchemaJson(), version.getOutputSchemaJson());
        skill.setStatus(version.getStatus()); skill.setVersionNo(skill.getVersionNo() + 1);
        mapper.updateById(skill); snapshot(skill); return skill;
    }

    private void snapshot(IqcSkill skill) {
        IqcSkillVersion version = new IqcSkillVersion(); version.setSkillId(skill.getId()); version.setVersionNo(skill.getVersionNo());
        version.setName(skill.getName()); version.setCode(skill.getCode()); version.setDescription(skill.getDescription());
        version.setInstructions(skill.getInstructions()); version.setInputSchemaJson(skill.getInputSchemaJson());
        version.setOutputSchemaJson(skill.getOutputSchemaJson()); version.setStatus(skill.getStatus()); versionMapper.insert(version);
    }

    private IqcSkill findByCode(String code) {
        return mapper.selectOne(Wrappers.<IqcSkill>lambdaQuery().eq(IqcSkill::getCode, code));
    }
    private IqcSkill require(String id) {
        IqcSkill skill = mapper.selectById(id);
        if (skill == null) throw IqcException.notFound("Skill 不存在: " + id);
        return skill;
    }
    private void validate(String name, String code, String instructions, String input, String output) {
        if (blank(name) || blank(code) || blank(instructions)) throw IqcException.invalidArgument("Skill 名称、编码和指令不能为空");
        if (!code.matches("[A-Z][A-Z0-9_]{1,63}")) throw IqcException.invalidArgument("Skill 编码必须为大写字母、数字或下划线");
        if (instructions.length() > 16000) throw IqcException.invalidArgument("Skill 指令不能超过 16000 字符");
        validateJson(input, "输入 Schema"); validateJson(output, "输出 Schema");
    }
    private void validateJson(String value, String label) {
        if (blank(value)) return;
        try { if (!objectMapper.readTree(value).isObject()) throw IqcException.invalidArgument(label + " 必须是 JSON 对象"); }
        catch (IqcException exception) { throw exception; }
        catch (Exception exception) { throw IqcException.invalidArgument(label + " 不是有效 JSON", exception); }
    }
    private static void apply(IqcSkill skill, String name, String code, String description, String instructions, String input, String output) {
        skill.setName(name.trim()); skill.setCode(code.trim()); skill.setDescription(description);
        skill.setInstructions(instructions.trim()); skill.setInputSchemaJson(input); skill.setOutputSchemaJson(output);
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
