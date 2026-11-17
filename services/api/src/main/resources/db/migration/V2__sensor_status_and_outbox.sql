-- The status a sensor was last found in, and since when. The status shown by the API is still worked out
-- from the age of the latest reading at the time of the request; this column is what changes are detected against.
alter table sensor add column status text not null default 'online';
alter table sensor add column status_since timestamptz not null default now();

-- Every change of status, for the record and for the API.
create table sensor_status_change (
    id               uuid primary key,
    sensor_id        text not null,
    status           text not null,
    previous_status  text not null,
    changed_at       timestamptz not null,
    last_reading_ts  timestamptz not null
);

create index sensor_status_change_changed_at_idx on sensor_status_change (changed_at desc);

-- Transactional outbox: an event to publish is written here in the same transaction as the change it reports,
-- and sent to Kafka afterwards. So an event is never published for a change that was rolled back, and never
-- lost for a change that was committed.
create table outbox_event (
    id            uuid primary key,
    topic         text not null,
    message_key   text,
    payload       text not null,
    created_at    timestamptz not null default now(),
    published_at  timestamptz
);

create index outbox_event_unpublished_idx on outbox_event (created_at) where published_at is null;
