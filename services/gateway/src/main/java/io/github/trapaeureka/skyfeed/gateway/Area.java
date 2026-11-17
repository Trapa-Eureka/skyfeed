package io.github.trapaeureka.skyfeed.gateway;

/** A map area in degrees: a box from (lamin, lomin) to (lamax, lomax), edges included. */
public record Area(double lamin, double lomin, double lamax, double lomax) {

    public Area {
        if (!(lamin >= -90 && lamax <= 90 && lomin >= -180 && lomax <= 180)) {
            throw new IllegalArgumentException("area is outside latitude -90..90 or longitude -180..180");
        }
        if (!(lamin < lamax && lomin < lomax)) {
            throw new IllegalArgumentException("area needs lamin < lamax and lomin < lomax");
        }
    }

    public boolean contains(double lat, double lon) {
        return lat >= lamin && lat <= lamax && lon >= lomin && lon <= lomax;
    }
}
