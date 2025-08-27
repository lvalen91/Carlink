//
//  Generated file. Do not edit.
//

// clang-format off

#include "generated_plugin_registrant.h"

#include <carlink/carlink_plugin.h>

void fl_register_plugins(FlPluginRegistry* registry) {
  g_autoptr(FlPluginRegistrar) carlink_registrar =
      fl_plugin_registry_get_registrar_for_plugin(registry, "CarlinkPlugin");
  carlink_plugin_register_with_registrar(carlink_registrar);
}
