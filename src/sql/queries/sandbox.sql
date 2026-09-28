-- Playground, use your editors REPL for SQL!
dt;

SELECT
    *,
    st_astext (geom)
FROM
    locations;

;

-- Coordinates are (longitude, latitude) in WGS84 (SRID 4326)
INSERT INTO locations (name, geom)
    VALUES ('San Francisco', ST_SetSRID (ST_Point (-122.4194, 37.7749), 4326));

INSERT INTO locations (name, geom)
VALUES
    ('Denver', ST_SetSRID (ST_Point (-104.9903, 39.7392), 4326)),
    ('Chicago', ST_SetSRID (ST_Point (-87.6298, 41.8781), 4326)),
    ('Atlanta', ST_SetSRID (ST_Point (-84.3880, 33.7490), 4326)),
    ('Paris', ST_SetSRID (ST_Point (2.3522, 48.8566), 4326)),
    ('Berlin', ST_SetSRID (ST_Point (13.4050, 52.5200), 4326)),
    ('Tehran', ST_SetSRID (ST_Point (51.3890, 35.6892), 4326));

INSERT INTO locations (name, geom)
    VALUES ('Mexico City', ST_SetSRID (ST_Point (-99.1332, 19.4326), 4326));

INSERT INTO locations (name, geom)
    VALUES ('Beijing', ST_SetSRID (ST_Point (116.4074, 39.9042), 4326));

INSERT INTO locations (name, geom)
    VALUES ('Moscow', ST_SetSRID (ST_Point (37.6173, 55.7558), 4326));

INSERT INTO locations (name, geom)
    VALUES ('London', ST_SetSRID (ST_Point (-0.1276, 51.5074), 4326));

INSERT INTO locations (name, geom)
    VALUES ('Dubai', ST_SetSRID (ST_Point (55.2708, 25.2048), 4326));

INSERT INTO locations (name, geom)
    VALUES ('Bangalore', ST_SetSRID (ST_Point (77.5946, 12.9716), 4326));

INSERT INTO locations (name, geom)
    VALUES ('Cairo', ST_SetSRID (ST_Point (31.2357, 30.0444), 4326));

INSERT INTO locations (name, geom)
    VALUES ('Tokyo', ST_SetSRID (ST_Point (139.6917, 35.6895), 4326));

-- Tropical Rainforest (Amazon Basin)
INSERT INTO locations (name, geom)
    VALUES ('Manaus', ST_SetSRID (ST_Point (-60.0212, -3.1190), 4326));

-- Equatorial Rainforest (Southeast Asia)
INSERT INTO locations (name, geom)
    VALUES ('Singapore', ST_SetSRID (ST_Point (103.8198, 1.3521), 4326));

-- Tropical Savanna / Highland
INSERT INTO locations (name, geom)
    VALUES ('Nairobi', ST_SetSRID (ST_Point (36.8219, -1.2921), 4326));

-- Tropical Wet / Coastal Savanna (West Africa)
INSERT INTO locations (name, geom)
    VALUES ('Lagos', ST_SetSRID (ST_Point (3.3792, 6.5244), 4326));

-- Mediterranean (Southern Hemisphere)
INSERT INTO locations (name, geom)
    VALUES ('Cape Town', ST_SetSRID (ST_Point (18.4241, -33.9249), 4326));

-- Humid Subtropical (Southern Hemisphere)
INSERT INTO locations (name, geom)
    VALUES ('Sydney', ST_SetSRID (ST_Point (151.2093, -33.8688), 4326));

-- Temperate Grassland / Pampas
INSERT INTO locations (name, geom)
    VALUES ('Buenos Aires', ST_SetSRID (ST_Point (-58.3816, -34.6037), 4326));

-- Arid Coastal Desert (Atacama edge)
INSERT INTO locations (name, geom)
    VALUES ('Lima', ST_SetSRID (ST_Point (-77.0428, -12.0464), 4326));

-- Boreal Forest / Continental Subarctic
INSERT INTO locations (name, geom)
    VALUES ('Fairbanks', ST_SetSRID (ST_Point (-147.7164, 64.8401), 4326));

-- Subarctic / Oceanic (high latitude)
INSERT INTO locations (name, geom)
    VALUES ('Reykjavik', ST_SetSRID (ST_Point (-21.9426, 64.1466), 4326));
