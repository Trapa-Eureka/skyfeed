-- Sensors: one row per sensor with its latest reading.
create table sensor (
    sensor_id      text primary key,
    lat            double precision not null,
    lon            double precision not null,
    last_ts        timestamptz not null,
    last_seq       bigint not null,
    temperature_c  double precision not null,
    signal_dbm     double precision not null,
    message_rate   double precision not null,
    updated_at     timestamptz not null default now()
);

-- Every reading. (sensor_id, ts, seq) identifies a reading, so storing the same one again changes nothing.
create table telemetry_reading (
    id             bigint generated always as identity primary key,
    sensor_id      text not null,
    ts             timestamptz not null,
    seq            bigint not null,
    temperature_c  double precision not null,
    signal_dbm     double precision not null,
    message_rate   double precision not null,
    received_at    timestamptz not null default now(),
    constraint telemetry_reading_identity unique (sensor_id, ts, seq)
);

-- Aircraft: one row per aircraft with its latest state.
create table aircraft (
    icao24           text primary key,
    callsign         text,
    origin_country   text,
    ts               timestamptz not null,
    last_contact     timestamptz not null,
    lat              double precision not null,
    lon              double precision not null,
    baro_altitude_m  double precision,
    geo_altitude_m   double precision,
    on_ground        boolean not null,
    velocity_ms      double precision,
    heading_deg      double precision,
    vertical_rate_ms double precision,
    squawk           text,
    source           text not null,
    updated_at       timestamptz not null default now()
);

create index aircraft_last_contact_idx on aircraft (last_contact);

-- Every state received, for the track. (icao24, last_contact) identifies a state.
create table aircraft_position (
    id               bigint generated always as identity primary key,
    icao24           text not null,
    ts               timestamptz not null,
    last_contact     timestamptz not null,
    lat              double precision not null,
    lon              double precision not null,
    baro_altitude_m  double precision,
    geo_altitude_m   double precision,
    on_ground        boolean not null,
    velocity_ms      double precision,
    heading_deg      double precision,
    vertical_rate_ms double precision,
    received_at      timestamptz not null default now(),
    constraint aircraft_position_identity unique (icao24, last_contact)
);

create index aircraft_position_track_idx on aircraft_position (icao24, ts);
