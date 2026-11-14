package fixture.before;

public final class Endpoint {
    private Endpoint() {}

    public static String transmit(String input) {
        return "speedtest.discovery.fixture.marker.v1".concat(":A:").concat(input);
    }
}
