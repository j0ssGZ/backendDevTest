#!/usr/bin/env python3
"""Extract compact raw HTTP samples from Influx JSON or k6 JSON-lines output."""
import csv
import datetime
import json
import sys
from pathlib import Path


def timestamp(value):
    main, fraction = value.rstrip('Z').split('.')
    return (datetime.datetime.strptime(main, '%Y-%m-%dT%H:%M:%S')
            .replace(tzinfo=datetime.timezone.utc).timestamp() + float('0.' + fraction))


mode, source, destination = sys.argv[1:]
if mode == 'influx':
    data = json.loads(Path(source).read_text())['results'][0]['series'][0]
    rows = [dict(zip(data['columns'], value)) for value in data['values']]
    # Query must use epoch=ns. Retain timestamps relative to the first response.
    first = min(row['time'] for row in rows)
    samples = [((row['time'] - first) / 1e6, row['scenario'], row['status'],
                row.get('error_code', ''), row['value']) for row in rows]
elif mode == 'k6':
    rows = []
    for line in Path(source).open():
        point = json.loads(line)
        if point.get('type') == 'Point' and point['metric'] == 'http_req_duration':
            rows.append(point['data'])
    first = min(timestamp(row['time']) for row in rows)
    samples = [((timestamp(row['time']) - first) * 1000, row['tags']['scenario'],
                row['tags']['status'], row['tags'].get('error_code', ''),
                round(row['value'], 6)) for row in rows]
else:
    raise SystemExit('Mode must be influx or k6')
with Path(destination).open('w') as output:
    writer = csv.writer(output, lineterminator='\n')
    writer.writerow(['end_offset_ms', 'scenario', 'status', 'error_code', 'duration_ms'])
    for elapsed, scenario, status, error, duration in samples:
        writer.writerow([f'{elapsed:.3f}', scenario, status, error, duration])
