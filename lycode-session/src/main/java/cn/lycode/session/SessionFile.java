package cn.lycode.session;

import cn.lycode.contracts.session.SessionEntry;
import cn.lycode.contracts.session.SessionHeader;
import java.util.List;

record SessionFile(SessionHeader header, List<SessionEntry> entries) {
}
