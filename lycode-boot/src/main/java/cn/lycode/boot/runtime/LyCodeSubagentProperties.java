package cn.lycode.boot.runtime;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "lycode.subagent")
public class LyCodeSubagentProperties {
    private List<String> command = new ArrayList<>();

    public List<String> getCommand() {
        return command;
    }

    public void setCommand(List<String> command) {
        this.command = command == null ? new ArrayList<>() : new ArrayList<>(command);
    }
}
