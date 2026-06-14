package cn.lypi.transport.tui;

final class OverlayCoordinator {
    private OverlayKind active = OverlayKind.NONE;

    void openSlash() {
        active = choose(active, OverlayKind.SLASH);
    }

    void openFileMention() {
        active = choose(active, OverlayKind.FILE);
    }

    void openDiff() {
        active = choose(active, OverlayKind.DIFF);
    }

    void openPermission() {
        active = choose(active, OverlayKind.PERMISSION);
    }

    OverlayKind active() {
        return active;
    }

    private OverlayKind choose(OverlayKind current, OverlayKind candidate) {
        return candidate.ordinal() >= current.ordinal() ? candidate : current;
    }
}
