package fixture.ambiguous;

public final class Alpha {
    private Alpha() {}

    public static String one(String input) {
        return "speedtest.discovery.fixture.marker.v1".concat(":C1:").concat(input);
    }
}
