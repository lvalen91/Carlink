import 'dart:io';
import 'dart:typed_data';

import 'package:permission_handler/permission_handler.dart';
import 'package:path_provider/path_provider.dart';

class FileWriter {
  static Future<File?> writeFileToDownloadsDir(Uint8List data, String name,
      {FileMode mode = FileMode.write}) async {
    try {
      print("[FILE_WRITER] Starting file write for: $name");
      
      // CORRECT APPROACH for minSdk 31+: Use app-specific external storage first
      // This is the recommended approach per Android documentation
      try {
        // Get app-specific external directory (no permissions required since API 29)
        final Directory? externalDir = await getExternalStorageDirectory();
        if (externalDir != null) {
          final File file = File('${externalDir.path}/$name');
          print("[FILE_WRITER] Writing to app-specific external directory: ${file.path}");
          
          await file.writeAsBytes(data, mode: mode, flush: true);
          print("[FILE_WRITER] Successfully wrote file to: ${file.path}");
          return file;
        }
      } catch (e) {
        print("[FILE_WRITER] App-specific external directory failed: $e");
      }
      
      // Secondary approach: App-specific internal directory (always available)
      try {
        final Directory appDocDir = await getApplicationDocumentsDirectory();
        final File file = File('${appDocDir.path}/$name');
        print("[FILE_WRITER] Writing to app documents directory: ${file.path}");
        
        await file.writeAsBytes(data, mode: mode, flush: true);
        print("[FILE_WRITER] Successfully wrote file to: ${file.path}");
        return file;
        
      } catch (e) {
        print("[FILE_WRITER] App documents directory failed: $e");
      }
      
      // NOTE: Direct access to public Downloads is discouraged for minSdk 31+
      // According to Android documentation, apps should:
      // 1. Use app-specific directories (above)
      // 2. Use MediaStore API for shared media files  
      // 3. Use Storage Access Framework for document access
      //
      // For CarPlay media covers, app-specific storage is the correct choice
      // as these are temporary files used internally by the app.
      
      print("[FILE_WRITER] All storage options failed");
      return null;
      
    } catch (e) {
      print("[FILE_WRITER] Unexpected error: $e");
      return null;
    }
  }
}
