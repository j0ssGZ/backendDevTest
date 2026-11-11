#!/usr/bin/env python3
"""Summarize retained HTTP samples. Standard library only; no network calls."""
import csv
import json
import math
from collections import Counter, defaultdict
from pathlib import Path


def percentile(values, p):
    ordered = sorted(values)
    rank = (len(ordered) - 1) * p
    lower, upper = math.floor(rank), math.ceil(rank)
    return ordered[lower] + (ordered[upper] - ordered[lower]) * (rank - lower)


def latency(values):
    return {key: round(value, 3) for key, value in {
        'mean_ms': sum(values) / len(values), 'median_ms': percentile(values, .5),
        'p90_ms': percentile(values, .9), 'p95_ms': percentile(values, .95),
        'max_ms': max(values),
    }.items()}


def summarize(path, expectations):
    groups = defaultdict(list)
    with path.open() as source:
        for row in csv.DictReader(source):
            groups[row['scenario']].append(row)
    result = {}
    for scenario, rows in groups.items():
        statuses = Counter(row['status'] for row in rows)
        durations = [float(row['duration_ms']) for row in rows]
        # A response's duration excludes blocked/connect time. This is an observed
        # HTTP span estimate, not a precise scheduled-window or client-end rate.
        span = (max(float(row['end_offset_ms']) for row in rows)
                - min(float(row['end_offset_ms']) - float(row['duration_ms']) for row in rows)) / 1000
        wanted = str(expectations[scenario])
        expected = statuses[wanted]
        first_end = min(float(row['end_offset_ms']) for row in rows)
        result[scenario] = {
            'completed': len(rows), 'statuses': dict(statuses),
            'expected_status': int(wanted),
            'classification': 'Status comparison only; matching 504 does not identify timeout stage', 'matching_status_count': expected,
            'other_status_count': len(rows) - expected,
            'other_status_percent': round(100 * (len(rows) - expected) / len(rows), 3),
            'transport_status_zero': statuses['0'],
            'timeout_504': statuses['504'],
            'timeout_504_under_2s': sum(row['status'] == '504' and float(row['duration_ms']) < 2000 for row in rows),
            'timeout_504_at_least_2s': sum(row['status'] == '504' and float(row['duration_ms']) >= 2000 for row in rows), 'unavailable_503': statuses['503'],
            'scheduled_window_seconds': 10,
            'completions_per_10s': round(len(rows) / 10, 3),
            'observed_http_span_seconds': round(span, 6),
            'observed_span_rps': round(len(rows) / span, 3),
            **latency(durations),
            'latency_by_status': {status: latency([float(row['duration_ms']) for row in rows
                                                  if row['status'] == status]) for status in statuses},
            'first_second_statuses': dict(Counter(row['status'] for row in rows
                if float(row['end_offset_ms']) < first_end + 1000)),
        }
    return result


if __name__ == '__main__':
    root = Path(__file__).parent
    output = {'official': summarize(root / 'official-http.csv',
              {'normal': 200, 'notFound': 404, 'error': 502, 'slow': 200, 'verySlow': 504})}
    for name in ('cold-normal', 'warm-normal', 'cold-slow', 'warm-slow', 'candidate-serial-slow'):
        output[name] = summarize(root / (name + '-http.csv'), {'diagnostic': 200})['diagnostic']
    print(json.dumps(output, indent=2))
