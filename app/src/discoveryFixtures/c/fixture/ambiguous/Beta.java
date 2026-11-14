package fixture.ambiguous;

public final class Beta {
    private Beta() {}

    public static String two(String input) {
        return "speedtest.discovery.fixture.marker.v1".concat(":C2:").concat(input);
    }
}
