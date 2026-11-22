`index-template.json` maps the structured log fields (keyword IDs for exact trace/correlation lookups,
replicas = 0 for a single node). `scripts/start-observability.ps1 -Logging` installs it automatically:

    PUT http://localhost:9200/_index_template/faultlab-logs
