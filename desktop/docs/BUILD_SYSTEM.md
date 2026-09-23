# Artemis Build System Guide

## Overview

The Artemis build system produces comprehensive, production-ready installers for all major platforms, including universal binaries that support multiple architectures without installation complexity.

## Build Artifacts

### Windows
- **Universal Installer** (`artemis-windows-universal-installer-{version}.exe`)
  - Single installer supporting both x64 and ARM64
  - Automatic architecture detection
  - Includes Visual C++ 2022 Redistributables for both architectures
  - Uses WiX Toolset v5 bundle technology

- **Individual Installers**
  - `artemis-windows-installer-{version}.msi` (x64)
  - `artemis-windows-arm64-installer-{version}.msi` (ARM64)
  
- **Portable Packages**  
  - `artemis-windows-portable-{version}.zip` (x64)
  - `artemis-windows-arm64-portable-{version}.zip` (ARM64)

### macOS
- **Universal DMG** (`artemis-macos-universal-{version}.dmg`)
  - Native support for Intel (x86_64) and Apple Silicon (arm64)
  - Single installer for all Mac systems
  - Code signed and notarized (when credentials available)
  - Professional DMG packaging with create-dmg

### Linux
- **x86_64 Packages**
  - `artemis-linux-{version}.tar.gz` (Basic binary)
  - `artemis-appimage-{version}-x86_64.AppImage` (Portable)
  - `artemis-flatpak-{version}.flatpak` (Sandboxed)

- **ARM64 Packages**  
  - `artemis-raspberry-pi-arm64-{version}.tar.gz` (Raspberry Pi 4/5)
  - Cross-compiled for optimal Raspberry Pi performance

- **Specialized Builds**
  - `artemis-steamdeck-{version}.tar.gz` (Steam Deck optimized)

## Architecture Support

| Platform | x86_64 | ARM64 | Universal |
|----------|--------|--------|-----------|
| Windows  | ✅ | ✅ | ✅ (Bundle) |
| macOS    | ✅ | ✅ | ✅ (Fat Binary) |
| Linux    | ✅ | ✅ (RPi) | ❌ |

## Build Scripts

### Windows
- `scripts/build-artemis-arch.bat` - Architecture-specific builds
- `scripts/generate-bundle.bat` - Universal installer generation
- Supports cross-compilation for ARM64 from x64 host

### macOS  
- `scripts/generate-dmg.sh` - Universal DMG generation
- Automatically builds fat binaries with both architectures
- Handles code signing and notarization

### Linux
- `scripts/build-appimage.sh` - AppImage generation
- Cross-compilation setup for ARM64/Raspberry Pi
- Steam Deck specific optimizations

## Local Development

### Building Universal Windows Installer
```batch
# Build x64 version
scripts\build-artemis-arch.bat release

# Build ARM64 version (requires ARM64 Qt)
scripts\build-artemis-arch.bat release

# Generate universal bundle
scripts\generate-bundle.bat release
```

### Building Universal macOS DMG
```bash
# Single command builds universal binary and packages DMG
./scripts/generate-dmg.sh Release
```

### Building Raspberry Pi ARM64
```bash
# Requires cross-compilation environment
sudo apt-get install gcc-aarch64-linux-gnu g++-aarch64-linux-gnu

# Configure and build
qmake6 artemis.pro CONFIG+=release QMAKE_CC=aarch64-linux-gnu-gcc
make -j$(nproc)
```

## CI/CD Pipeline

The GitHub Actions workflow (`dev-build.yml`) automatically:

1. **Detects platforms** and builds appropriate artifacts
2. **Generates universal installers** where supported  
3. **Cross-compiles** for additional architectures
4. **Packages professionally** with proper metadata
5. **Creates releases** with comprehensive artifact sets

### Build Matrix
- **Windows**: x64, ARM64, Universal Bundle
- **macOS**: Universal (x86_64 + arm64)  
- **Linux**: x86_64, ARM64 (Raspberry Pi), AppImage, Flatpak
- **Steam**: Steam Deck optimized

## Comparison with Moonlight-Qt

### What We Now Match
✅ Universal Windows installer (WiX bundle)  
✅ Universal macOS DMG (fat binary)  
✅ Professional packaging and signing  
✅ ARM64 support across platforms  

### What We've Enhanced
🚀 **Raspberry Pi ARM64** - Dedicated cross-compiled builds  
🚀 **Steam Deck optimization** - Specialized gaming build  
🚀 **Comprehensive CI/CD** - Automated universal builds  
🚀 **Better documentation** - Clear architecture support matrix

## Requirements

### Development Environment
- **Windows**: Visual Studio 2022, Qt 6.8+, WiX Toolset v5
- **macOS**: Xcode, Qt 6.8+, create-dmg
- **Linux**: GCC, Qt 6.8+, Cross-compilation tools for ARM64

### Runtime Dependencies
All builds include or automatically install required runtime components:
- Visual C++ Redistributables (Windows)
- Qt libraries (bundled)
- Multimedia codecs (system-dependent)

## Performance Notes

- **Universal binaries** have no runtime performance penalty
- **ARM64 builds** provide optimal performance on native hardware  
- **Cross-compiled builds** are fully optimized for target architecture
- **Hardware acceleration** supported where available

## Troubleshooting

### Windows ARM64 Issues
- Ensure Visual Studio ARM64 build tools installed
- Verify Qt ARM64 binaries in PATH
- Check cross-compilation toolchain setup

### macOS Universal Binary Issues
- Verify Xcode command line tools installed  
- Check Qt installation includes both architectures
- Ensure proper code signing certificates (for distribution)

### Linux Cross-Compilation Issues
- Install complete cross-compilation toolchain
- Verify ARM64 system libraries available
- Check PKG_CONFIG_PATH for target architecture

## Future Enhancements

- **Linux Universal AppImage** - Single AppImage for multiple architectures
- **Windows ARM64 Native Builds** - Build directly on ARM64 hardware
- **Automated Testing** - Architecture-specific test suites
- **Performance Profiling** - Architecture-optimized builds
