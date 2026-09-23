# Windows ARM64 Support

Artemis Qt now includes native support for Windows ARM64 devices, including:

- **Surface Pro X** and other Windows on ARM laptops
- **Dev Kit 2023** and ARM64 development machines
- **Windows 11 ARM64** virtual machines

## Build Artifacts

The CI/CD system now automatically generates ARM64 builds alongside x64 builds:

- `artemis-windows-arm64-installer-{version}.msi` - MSI installer for ARM64
- `artemis-windows-arm64-portable-{version}.zip` - Portable ZIP for ARM64

## Technical Details

### Architecture Detection
The build system automatically detects ARM64 Qt installations and configures cross-compilation:

```batch
if not x%QT_PATH:_arm64=%==x%QT_PATH% (
    set ARCH=arm64
    rem Replace the _arm64 suffix with _64 to get the x64 bin path
    set HOSTBIN_PATH=%QT_PATH:_arm64=_64%
    set WINDEPLOYQT_CMD=!HOSTBIN_PATH!\windeployqt.exe --qtpaths %QT_PATH%\qtpaths.bat
)
```

### Cross-Compilation
Builds use Visual Studio's cross-compilation toolchain:

```yaml
- name: Setup MSVC for ARM64
  uses: ilammy/msvc-dev-cmd@v1
  with:
    arch: amd64_arm64  # Cross-compile from x64 host to ARM64 target
```

### Libraries
All required ARM64 libraries are included:

- **SDL2** - Input and window management
- **FFmpeg** - Video decoding with hardware acceleration
- **OpenSSL** - Secure connections
- **Discord RPC** - Rich presence support
- **libplacebo** - Advanced video processing

### Qt Configuration
Uses Qt's official ARM64 builds:

```yaml
arch: 'win64_msvc2022_arm64'
```

## Local Development

To build ARM64 locally, you need:

1. **Visual Studio 2022** with ARM64 build tools
2. **Qt 6.8.1** with ARM64 support (`win64_msvc2022_arm64`)
3. **WiX Toolset v5** for installer generation

Build command:
```batch
scripts\build-artemis-arch.bat release
```

The build script will automatically detect ARM64 Qt and configure appropriately.

## Testing

### Emulation Testing
You can test ARM64 builds on x64 Windows using emulation:
- Download the ARM64 portable build
- Extract and run - should work via Windows' ARM64 emulation

### Native Testing
For native testing, use:
- **Windows 11 ARM64** virtual machine
- **Surface Pro X** or similar ARM64 device
- **Windows Dev Kit 2023**

## Performance Notes

- **Native ARM64** builds provide optimal performance on ARM64 hardware
- **Hardware acceleration** is supported on compatible ARM64 GPUs
- **Emulated x64** builds will work but with reduced performance

## Troubleshooting

### Missing Dependencies
If you see DLL errors, ensure all ARM64 runtime dependencies are available:
- Install **Visual C++ 2022 ARM64 Redistributable**
- Check that ARM64 libraries are in the application directory

### Cross-Compilation Issues
If builds fail with cross-compilation errors:
- Verify Visual Studio ARM64 build tools are installed
- Check that Qt ARM64 binaries are properly installed
- Ensure PATH includes both x64 and ARM64 Qt directories

## Future Enhancements

Potential improvements for ARM64 support:

- **Hardware-specific optimizations** for Surface Pro X
- **Better power management** for ARM64 devices  
- **Touch input enhancements** for ARM64 tablets
- **GPU acceleration** improvements for ARM64 graphics

## Related Issues

This implementation addresses:
- Native ARM64 support for Windows on ARM devices
- Improved performance over x64 emulation
- Complete parity with x64 feature set
- Automated CI/CD for ARM64 builds
