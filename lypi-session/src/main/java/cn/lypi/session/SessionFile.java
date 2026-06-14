package cn.lypi.session;

import cn.lypi.contracts.session.SessionEntry;
import cn.lypi.contracts.session.SessionHeader;
import java.util.List;

record SessionFile(SessionHeader header, List<SessionEntry> entries) {
}
