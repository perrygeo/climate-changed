-- Playground, use your editors REPL for SQL!
dt;

SELECT
    *,
    st_astext (geom)
FROM
    locations;
