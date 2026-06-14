package cn.lypi.resource;

import cn.lypi.contracts.skill.SkillDescriptor;

/**
 * 按需加载 Skill 正文并生成激活记录。
 */
public interface SkillActivationService {
    /**
     * 激活 Skill 并读取正文。
     *
     * NOTE: 只读取 descriptor 指向的 Skill 文件，不写 session entry。
     */
    SkillActivationResult activate(SkillDescriptor descriptor, String activatedReason);
}
