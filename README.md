# TrackSpeed Android

Professional sprint timing app for Android, designed to work seamlessly with the iOS TrackSpeed (Speed Swift) app.

## Overview

TrackSpeed uses your phone's high-speed camera (120-240fps) to detect athlete gate crossings with millisecond precision. Two or more phones can be synchronized for split timing at different gate positions.

### Key Features

- **Single-phone timing** - Lap counter with photo-finish capture
- **Two-phone timing** - Synchronized start/finish gates
- **Cross-platform** - Android and iOS devices work together
- **High precision** - Sub-10ms timing accuracy with 240fps capture
- **Photo-finish** - Visual proof of crossing moment

## Project Structure

```
TrackSpeed-Android/
├── README.md                           # This file
├── docs/
│   ├── requirements/
│   │   └── PRD.md                      # Product Requirements Document
│   ├── architecture/
│   │   ├── TECH_SPEC.md                # Technical Specification
│   │   └── ARCHITECTURE.md             # Architecture Overview
│   ├── protocols/
│   │   └── CROSS_PLATFORM_PROTOCOL.md  # iOS-Android communication protocol
│   └── roadmap/
│       └── DEVELOPMENT_TODO.md         # Development task list
└── app/                                # Android app (to be created)
```

## Documentation

| Document | Description |
|----------|-------------|
| [PRD](docs/requirements/PRD.md) | Product requirements, features, success criteria |
| [Tech Spec](docs/architecture/TECH_SPEC.md) | Technical implementation details |
| [Architecture](docs/architecture/ARCHITECTURE.md) | Code organization and patterns |
| [Protocol](docs/protocols/CROSS_PLATFORM_PROTOCOL.md) | Cross-platform communication spec |
| [Roadmap](docs/roadmap/DEVELOPMENT_TODO.md) | Development phases and tasks |

## Technology Stack

| Component | Technology |
|-----------|------------|
| Language | Kotlin |
| UI | Jetpack Compose |
| Camera | Camera2 API |
| Pose Detection | Google ML Kit |
| Local Database | Room |
| Backend | Supabase |
| DI | Hilt |
| Async | Coroutines + Flow |

## Requirements

- Android 8.0+ (API 26+)
- Camera with high-speed capture support (120+ fps recommended)
- Bluetooth LE for local device pairing
- Internet for cloud relay mode

## Related Projects

- **Speed Swift** (iOS) - `/Users/sondre/Documents/App/speed-swift/`

## Development

### Setup

1. Open project in Android Studio
2. Add `local.properties` with Supabase credentials:
   ```
   SUPABASE_URL=your_url
   SUPABASE_ANON_KEY=your_key
   ```
3. Build and run

### Building

```bash
./gradlew assembleDebug
```

### Testing

```bash
./gradlew test          # Unit tests
./gradlew connectedTest # Instrumentation tests
```

## License

Proprietary - All rights reserved

## Contact

[Your contact info]
