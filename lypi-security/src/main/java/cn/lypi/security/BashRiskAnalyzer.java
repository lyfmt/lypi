package cn.lypi.security;

import cn.lypi.contracts.security.BashRiskAnalysis;

public interface BashRiskAnalyzer {
    /*
    * @status : 未完成
    * @summary : 分析 Bash 命令风险。
    *@description : 无法静态分析的命令必须标记为 unknown 并触发 ask 策略。
    *
    *
                              */
    BashRiskAnalysis analyze(String rawCommand);
}

