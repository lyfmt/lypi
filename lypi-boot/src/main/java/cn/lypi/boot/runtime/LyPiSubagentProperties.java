package cn.lypi.boot.runtime;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "lypi.subagent")
public class LyPiSubagentProperties {
    private List<String> command = new ArrayList<>();

    public List<String> getCommand() {
        return command;
    }

    public void setCommand(List<String> command) {
        this.command = command == null ? new ArrayList<>() : new ArrayList<>(command);
    }
}
