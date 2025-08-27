import 'dart:async';\nimport 'package:carlink/carlink.dart';
import 'package:carlink/carlink_platform_interface.dart';
import 'package:carlink/common.dart';
import 'package:carlink/driver/sendable.dart';
import 'package:flutter/material.dart';
import 'logger.dart';



class SettingsPage extends StatefulWidget {
  final Carlink? carlink;
  
  const SettingsPage({super.key, this.carlink});
  
  @override
  State<SettingsPage> createState() => _SettingsPageState();
}

class _SettingsPageState extends State<SettingsPage> {
  bool _isProcessing = false;
  late CarlinkState _currentState;
  Timer? _statePollingTimer;

  // Helper method to create simple messages with no payload
  SendableMessage _createSimpleMessage(MessageType type) {
    return _SimpleMessage(type);
  }

  Future<void> _disconnectPhone() async {
    if (_isProcessing || widget.carlink == null) return;
    
    setState(() {
      _isProcessing = true;
    });
    
    try {
      Logger.log("[SETTINGS] Sending disconnect phone command");
      // Create a simple sendable message for DisconnectPhone (0x0F)
      final disconnectMessage = _createSimpleMessage(MessageType.DisconnectPhone);
      await widget.carlink!.sendMessage(disconnectMessage);
      
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Phone disconnection command sent')),
        );
      }
    } catch (e) {
      Logger.log("[SETTINGS] Failed to disconnect phone: $e");
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Failed to disconnect phone: $e')),
        );
      }
    } finally {
      if (mounted) {
        setState(() {
          _isProcessing = false;
        });
      }
    }
  }

  Future<void> _closeDongle() async {
    if (_isProcessing || widget.carlink == null) return;
    
    setState(() {
      _isProcessing = true;
    });
    
    try {
      Logger.log("[SETTINGS] Sending close dongle command");
      // Create a simple sendable message for CloseDongle (0x15)
      final closeMessage = _createSimpleMessage(MessageType.CloseDongle);
      await widget.carlink!.sendMessage(closeMessage);
      
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Dongle close command sent')),
        );
      }
    } catch (e) {
      Logger.log("[SETTINGS] Failed to close dongle: $e");
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Failed to close dongle: $e')),
        );
      }
    } finally {
      if (mounted) {
        setState(() {
          _isProcessing = false;
        });
      }
    }
  }

  Future<void> _resetDevice() async {
    if (_isProcessing) return;
    
    setState(() {
      _isProcessing = true;
    });
    
    try {
      Logger.log("[SETTINGS] Performing device reset");
      await CarlinkPlatform.instance.resetDevice();
      
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Device reset completed')),
        );
      }
    } catch (e) {
      Logger.log("[SETTINGS] Failed to reset device: $e");
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Failed to reset device: $e')),
        );
      }
    } finally {
      if (mounted) {
        setState(() {
          _isProcessing = false;
        });
      }
    }
  }

  Future<void> _resetH264Renderer() async {
    if (_isProcessing) return;
    
    setState(() {
      _isProcessing = true;
    });
    
    try {
      Logger.log("[SETTINGS] Resetting H264 renderer");
      await CarlinkPlatform.instance.resetH264Renderer();
      
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('H264 renderer reset completed')),
        );
      }
    } catch (e) {
      Logger.log("[SETTINGS] Failed to reset H264 renderer: $e");
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Failed to reset H264 renderer: $e')),
        );
      }
    } finally {
      if (mounted) {
        setState(() {
          _isProcessing = false;
        });
      }
    }
  }

  @override
  void initState() {
    super.initState();
    _currentState = widget.carlink?.state ?? CarlinkState.disconnected;
    _startStatePolling();
  }

  @override
  void dispose() {
    _statePollingTimer?.cancel();
    super.dispose();
  }

  void _startStatePolling() {
    // Poll the state every 500ms to catch changes
    _statePollingTimer = Timer.periodic(const Duration(milliseconds: 500), (timer) {
      if (widget.carlink != null && mounted) {
        final newState = widget.carlink!.state;
        if (newState != _currentState) {
          setState(() {
            _currentState = newState;
          });
          Logger.log("[SETTINGS] State changed to: ${newState.name}");
        }
      }
    });
  }

  String _getStateDisplayText(CarlinkState state) {
    switch (state) {
      case CarlinkState.disconnected:
        return 'Disconnected';
      case CarlinkState.connecting:
        return 'Connecting...';
      case CarlinkState.deviceConnected:
        return 'Device Connected';
      case CarlinkState.streaming:
        return 'Active Projection Session';
    }
  }

  Color _getStateColor(CarlinkState state) {
    switch (state) {
      case CarlinkState.disconnected:
        return Colors.red;
      case CarlinkState.connecting:
        return Colors.orange;
      case CarlinkState.deviceConnected:
        return Colors.blue;
      case CarlinkState.streaming:
        return Colors.green;
    }
  }

  bool _isProjectionActive() {
    return _currentState == CarlinkState.streaming;
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      appBar: AppBar(
        title: const Text('Dongle Settings'),
        backgroundColor: Colors.grey[900],
        foregroundColor: Colors.white,
      ),
      body: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Card(
              color: Colors.grey[800],
              child: Padding(
                padding: const EdgeInsets.all(16.0),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'Dongle Control',
                      style: TextStyle(
                        color: Colors.white,
                        fontSize: 18,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                    const SizedBox(height: 16),
                    
                    // Disconnect Phone Button
                    SizedBox(
                      width: double.infinity,
                      child: ElevatedButton.icon(
                        onPressed: _isProcessing || widget.carlink == null ? null : _disconnectPhone,
                        icon: const Icon(Icons.phone_disabled),
                        label: const Text('Disconnect Phone'),
                        style: ElevatedButton.styleFrom(
                          backgroundColor: Colors.orange[700],
                          foregroundColor: Colors.white,
                          padding: const EdgeInsets.symmetric(vertical: 12),
                        ),
                      ),
                    ),
                    const SizedBox(height: 8),
                    
                    // Close Dongle Button
                    SizedBox(
                      width: double.infinity,
                      child: ElevatedButton.icon(
                        onPressed: _isProcessing || widget.carlink == null ? null : _closeDongle,
                        icon: const Icon(Icons.power_off),
                        label: const Text('Close Dongle'),
                        style: ElevatedButton.styleFrom(
                          backgroundColor: Colors.red[700],
                          foregroundColor: Colors.white,
                          padding: const EdgeInsets.symmetric(vertical: 12),
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
            
            const SizedBox(height: 16),
            
            Card(
              color: Colors.grey[800],
              child: Padding(
                padding: const EdgeInsets.all(16.0),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'System Reset',
                      style: TextStyle(
                        color: Colors.white,
                        fontSize: 18,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                    const SizedBox(height: 16),
                    
                    // Reset H264 Renderer Button
                    SizedBox(
                      width: double.infinity,
                      child: ElevatedButton.icon(
                        onPressed: _isProcessing ? null : _resetH264Renderer,
                        icon: const Icon(Icons.video_settings),
                        label: const Text('Reset Video Decoder'),
                        style: ElevatedButton.styleFrom(
                          backgroundColor: Colors.blue[700],
                          foregroundColor: Colors.white,
                          padding: const EdgeInsets.symmetric(vertical: 12),
                        ),
                      ),
                    ),
                    const SizedBox(height: 8),
                    
                    // Reset Device Button
                    SizedBox(
                      width: double.infinity,
                      child: ElevatedButton.icon(
                        onPressed: _isProcessing ? null : _resetDevice,
                        icon: const Icon(Icons.restart_alt),
                        label: const Text('Reset USB Device'),
                        style: ElevatedButton.styleFrom(
                          backgroundColor: Colors.red[900],
                          foregroundColor: Colors.white,
                          padding: const EdgeInsets.symmetric(vertical: 12),
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
            
            const SizedBox(height: 16),
            
            Card(
              color: Colors.grey[800],
              child: Padding(
                padding: const EdgeInsets.all(16.0),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'Status',
                      style: TextStyle(
                        color: Colors.white,
                        fontSize: 18,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                    const SizedBox(height: 8),
                    Text(
                      'Carlink Device: ${widget.carlink != null ? 'Connected' : 'Disconnected'}',
                      style: TextStyle(
                        color: widget.carlink != null ? Colors.green : Colors.red,
                        fontSize: 14,
                      ),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      'Projection Status: ${_getStateDisplayText(_currentState)}',
                      style: TextStyle(
                        color: _getStateColor(_currentState),
                        fontSize: 14,
                        fontWeight: _isProjectionActive() ? FontWeight.bold : FontWeight.normal,
                      ),
                    ),
                    if (_isProjectionActive())
                      const Padding(
                        padding: EdgeInsets.only(top: 4),
                        child: Row(
                          children: [
                            Icon(Icons.cast_connected, color: Colors.green, size: 16),
                            SizedBox(width: 4),
                            Text(
                              'Phone projection is active',
                              style: TextStyle(color: Colors.green, fontSize: 12, fontStyle: FontStyle.italic),
                            ),
                          ],
                        ),
                      ),
                    if (_isProcessing)
                      const Padding(
                        padding: EdgeInsets.only(top: 8),
                        child: Row(
                          children: [
                            SizedBox(
                              width: 16,
                              height: 16,
                              child: CircularProgressIndicator(
                                strokeWidth: 2,
                                valueColor: AlwaysStoppedAnimation<Color>(Colors.white),
                              ),
                            ),
                            SizedBox(width: 8),
                            Text(
                              'Processing...',
                              style: TextStyle(color: Colors.white, fontSize: 12),
                            ),
                          ],
                        ),
                      ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

// Simple message class for commands with no payload
class _SimpleMessage extends SendableMessage {
  _SimpleMessage(MessageType type) : super(type);
}
