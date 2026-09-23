CREATE EXTENSION IF NOT EXISTS postgis;

--;;
CREATE TABLE locations (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name text NOT NULL,
    geom GEOMETRY(point, 4326)
);
