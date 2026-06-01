package cn.lypi.contracts.boundary;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(value = "passed", allowGetters = true)
public record BoundaryCheckReport(
    List<BoundaryRuleResult> results
) {
    @JsonProperty("passed")
    public boolean passed() {
        return results.stream().allMatch(BoundaryRuleResult::passed);
    }
}
