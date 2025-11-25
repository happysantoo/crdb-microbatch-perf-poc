# Images Directory

This directory contains images and screenshots for documentation.

## Structure

```
images/
├── grafana-screenshots/    # Grafana dashboard screenshots
│   ├── dashboard-overview.png
│   ├── throughput-panel.png
│   ├── latency-panel.png
│   ├── connection-pool-panel.png
│   ├── memory-panel.png
│   ├── virtual-threads-panel.png
│   └── success-rate-panel.png
└── README.md               # This file
```

## How to Add Screenshots

### From Grafana:

1. **Navigate to the panel** you want to capture
2. **Take screenshot:**
   - **Mac**: Cmd+Shift+4, select area
   - **Windows**: Snipping Tool or Win+Shift+S
   - **Linux**: Use screenshot tool (Print Screen, etc.)
3. **Save to**: `documents/images/grafana-screenshots/`
4. **Use descriptive names**: e.g., `throughput-panel.png`

### Embedding in Markdown:

```markdown
![Alt Text](../images/grafana-screenshots/filename.png)
```

**Example:**
```markdown
![Throughput Metrics](../images/grafana-screenshots/throughput-panel.png)
*Caption describing what the screenshot shows*
```

## Image Guidelines

- **Format**: PNG preferred (better for graphs/charts)
- **Size**: Keep under 2MB if possible
- **Naming**: Use kebab-case (e.g., `connection-pool-panel.png`)
- **Alt Text**: Always provide descriptive alt text
- **Captions**: Add captions below images to explain context

