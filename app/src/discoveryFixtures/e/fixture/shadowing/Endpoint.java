package fixture.shadowing;

/** This anchored split implementation is shadowed by the base class definition. */
public final class Endpoint {
    private Endpoint() {}

    public static String transmit(String input) {
        return "speedtest.discovery.fixture.marker.v1".concat(":shadow-split:").concat(input);
    }
}
