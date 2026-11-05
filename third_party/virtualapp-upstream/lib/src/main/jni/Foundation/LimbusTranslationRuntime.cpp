#include "LimbusTranslationRuntime.h"
#include "LimbusTextLayoutPolicy.h"
#include "LimbusTranslationProvenance.h"
#include "../Substrate/SubstrateHook.h"

#include <android/log.h>
#include <algorithm>
#include <arpa/inet.h>
#include <atomic>
#include <cctype>
#include <dlfcn.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <string>
#include <time.h>
#include <unordered_map>
#include <unordered_set>
#include <vector>
#include <unistd.h>

#define LT_TAG "LimbusTranslation"
#define LT_LOGI(...) __android_log_print(ANDROID_LOG_INFO, LT_TAG, __VA_ARGS__)
#define LT_LOGW(...) __android_log_print(ANDROID_LOG_WARN, LT_TAG, __VA_ARGS__)

namespace {

const unsigned char kIndexMagic[] = {'L', 'Z', 'T', 'I', '1', 0};
const uint32_t kIndexSchema = 14;
const uint32_t kMaxEntries = 1000000;
const uint32_t kMaxStringBytes = 16 * 1024 * 1024;

pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
pthread_mutex_t g_font_state_lock = PTHREAD_MUTEX_INITIALIZER;
char g_active_pointer[PATH_MAX] = {};
std::unordered_map<std::string, std::string> g_index;
std::unordered_map<std::string, std::string> g_terms;
// 同文异译的完整文本回退。严格索引仍优先；回退表只用于避免低票冲突直接显示日文。
std::unordered_map<std::string, std::string> g_fallback_index;
// 带稳定对象 ID 的精确映射：ID、字段名与原文组成上下文键，优先于全局字符串索引。
std::unordered_map<std::string, std::string> g_context_index;
// 小贴士表按资源名和数字 ID 保存兼容译文，兼容游戏更新后仍被调用的旧版原文。
std::unordered_map<std::string, std::string> g_battle_hint_index;
// 部分人格列表会把汉化包标题中的换行压成空格或直接移除；该索引只保存由包内译文
// 推导出的原始分行，让运行时兼容不同页面，而无需硬编码任何人格名称或译文。
std::unordered_map<std::string, std::string> g_package_line_breaks;
struct TermTrieNode {
    std::unordered_map<unsigned char, size_t> children;
    const std::string *source = nullptr;
    const std::string *translation = nullptr;
};
std::vector<TermTrieNode> g_term_trie;
bool g_configured = false;
bool g_resolver_reported = false;
bool g_probe_started = false;
bool g_hook_installed = false;
bool g_font_attempted = false;
void *g_tmp_font_asset = nullptr;
void *g_tmp_regular_font_asset = nullptr;
void *g_ui_font = nullptr;
// Unity managed wrappers can outlive all scene-local component references. Keep
// the injected font assets pinned so their raw IL2CPP pointers never dangle
// after battle UI teardown and a later GC.
uintptr_t g_tmp_font_handle = 0;
uintptr_t g_tmp_regular_font_handle = 0;
uintptr_t g_ui_font_handle = 0;
std::unordered_map<void *, uintptr_t> g_styled_chinese_materials;
std::unordered_set<void *> g_pending_tmp_rebuilds;
struct MaterialVisualStyle {
    float face_dilate = 0.0f;
    float outline_width = 0.0f;
    float outline_softness = 0.0f;
    float underlay_offset_x = 0.0f;
    float underlay_offset_y = 0.0f;
    float underlay_dilate = 0.0f;
    float underlay_softness = 0.0f;
    float gradient_scale = 0.0f;
    float scale_ratio_a = 0.0f;
    float scale_ratio_b = 0.0f;
    float scale_ratio_c = 0.0f;
    float texture_width = 0.0f;
    float texture_height = 0.0f;
    float face_color[4] = {1.0f, 1.0f, 1.0f, 1.0f};
    float outline_color[4] = {0.0f, 0.0f, 0.0f, 1.0f};
    float underlay_color[4] = {0.0f, 0.0f, 0.0f, 0.0f};
    bool has_face_dilate = false;
    bool has_outline_width = false;
    bool has_outline_softness = false;
    bool has_underlay_offset_x = false;
    bool has_underlay_offset_y = false;
    bool has_underlay_dilate = false;
    bool has_underlay_softness = false;
    bool has_gradient_scale = false;
    bool has_scale_ratio_a = false;
    bool has_scale_ratio_b = false;
    bool has_scale_ratio_c = false;
    bool has_texture_width = false;
    bool has_texture_height = false;
    bool has_face_color = false;
    bool has_outline_color = false;
    bool has_underlay_color = false;
};
// 译文来源按 UTF-8 内容登记，避免游戏复制 IL2CPP String 后丢失仅依赖对象地址的字体标记。
std::unordered_set<std::string> g_translated_managed_texts;
// 弱句柄只证明同一个存活的托管对象来自接管层；不靠文本内容猜测是否已经翻译。
// 有界弱缓存只回收已死对象，GC 可正常回收字符串，地址复用也不会继承旧来源凭据。
constexpr size_t kMaxTranslatedOrigins = 262144;
struct TranslatedStringOrigin {
    void *object = nullptr;
    uintptr_t handle = 0;
};
std::vector<TranslatedStringOrigin> g_translated_origin_slots;
std::vector<size_t> g_free_translated_origin_slots;
size_t g_translated_origin_cursor = 0;
std::unordered_map<void *, uintptr_t> g_translated_string_origins;
struct TmpComponentState {
    uintptr_t instance_weak_handle = 0;
    void *font = nullptr;
    uintptr_t font_weak_handle = 0;
    std::string font_name;
    std::string battle_winrate_text;
    void *shared_material = nullptr;
    uintptr_t shared_material_weak_handle = 0;
    void *source_material = nullptr;
    uintptr_t source_material_weak_handle = 0;
    float line_spacing = 0.0f;
    bool has_line_spacing = false;
    float font_size = 0.0f;
    bool has_font_size = false;
    float font_size_min = 0.0f;
    bool has_font_size_min = false;
    float font_size_max = 0.0f;
    bool has_font_size_max = false;
    bool auto_sizing = false;
    bool has_auto_sizing = false;
    bool word_wrapping = false;
    bool has_word_wrapping = false;
    int32_t overflow_mode = 0;
    bool has_overflow_mode = false;
    int32_t font_style = 0;
    bool has_font_style = false;
    // TMP applies the visible state colour and gradients through the graphic's
    // vertex colours, not through the font material's white _FaceColor. These
    // values must survive the font/material replacement unchanged.
    float text_color[4] = {1.0f, 1.0f, 1.0f, 1.0f};
    bool has_text_color = false;
    float text_color_gradient[16] = {
            1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f,
            1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f};
    bool has_text_color_gradient = false;
    bool enable_vertex_gradient = false;
    bool has_enable_vertex_gradient = false;
    MaterialVisualStyle source_material_style;
    size_t source_visible_codepoints = 0;
    void *translated_material = nullptr;
    uintptr_t translated_material_weak_handle = 0;
    void *translated_material_font = nullptr;
    uintptr_t translated_material_font_weak_handle = 0;
    bool use_regular_title_font = false;
    bool use_battle_winrate_bold = false;
    bool preserve_source_outline = false;
    std::string known_text_kind;
};
std::unordered_map<void *, TmpComponentState> g_original_tmp_states;
// Source material presets can be shared by several TMP components and are
// mutated in place after set_text. Track each weak component owner so later
// colour/float changes can be mirrored to the correct Chinese clone.
std::unordered_map<void *, std::vector<uintptr_t>> g_source_material_owners;
struct UiComponentState {
    uintptr_t instance_weak_handle = 0;
    void *font = nullptr;
    uintptr_t font_weak_handle = 0;
    float line_spacing = 1.0f;
    bool has_line_spacing = false;
    int32_t font_size = 0;
    bool has_font_size = false;
    int32_t resize_min_size = 0;
    bool has_resize_min_size = false;
    int32_t resize_max_size = 0;
    bool has_resize_max_size = false;
    bool resize_best_fit = false;
    bool has_resize_best_fit = false;
    int32_t horizontal_overflow = 0;
    bool has_horizontal_overflow = false;
    int32_t vertical_overflow = 0;
    bool has_vertical_overflow = false;
};
std::unordered_map<void *, UiComponentState> g_original_ui_states;
uint64_t g_translation_hits = 0;
std::atomic<uint64_t> g_exact_matches{0};
std::atomic<uint64_t> g_layout_matches{0};
std::atomic<uint64_t> g_term_matches{0};
std::atomic<uint64_t> g_fallback_matches{0};
std::atomic<uint64_t> g_tmp_material_syncs{0};
std::atomic<uint64_t> g_tmp_material_clones{0};
std::atomic<uint64_t> g_tmp_underlay_rebinds{0};
std::atomic<uint64_t> g_tmp_title_style_logs{0};
std::atomic<uint64_t> g_battle_status_style_diagnostics{0};
std::atomic<uint64_t> g_battle_status_material_set_diagnostics{0};
std::atomic<uint64_t> g_regular_component_resets{0};
std::atomic<uint64_t> g_short_text_diagnostics{0};
std::atomic<uint64_t> g_navigation_refreshes{0};
std::atomic<uint64_t> g_food_title_diagnostics{0};
std::atomic<uint64_t> g_multiline_matches{0};
std::atomic<uint64_t> g_tag_repair_matches{0};
std::atomic<uint64_t> g_unmatched_texts{0};
std::atomic<uint64_t> g_long_text_fit_actions{0};
std::atomic<uint64_t> g_egogift_name_diagnostics{0};
std::atomic<uint64_t> g_fallback_primary_promotions{0};
std::atomic<uint64_t> g_tmp_font_reset_blocks{0};
char g_il2cpp_path[PATH_MAX] = {};

struct Il2CppStringLayout {
    void *klass;
    void *monitor;
    int32_t length;
    uint16_t chars[1];
};

typedef int32_t (*string_length_fn)(void *);
typedef const uint16_t *(*string_chars_fn)(void *);
typedef void *(*string_new_utf16_fn)(const uint16_t *, int32_t);
typedef void *(*array_addr_with_size_fn)(void *, int32_t, uint32_t);
// arm64 的句柄作为不透明整数传递，兼容 IL2CPP 的整数/指针宽度句柄表示。
typedef uintptr_t (*gchandle_new_fn)(void *, bool);
typedef uintptr_t (*gchandle_new_weakref_fn)(void *, bool);
typedef void *(*gchandle_get_target_fn)(uintptr_t);
typedef void (*gchandle_free_fn)(uintptr_t);
typedef void *(*create_dynamic_font_fn)(void *, int32_t, const void *);
typedef void *(*create_tmp_font_fn)(void *, const void *);
typedef void *(*runtime_invoke_fn)(const void *, void *, void **, void **);
typedef void *(*object_get_class_fn)(void *);
typedef void *(*object_unbox_fn)(void *);
typedef const void *(*class_get_method_global_fn)(void *, const char *, int);
typedef void *(*il2cpp_init_fn)(const char *);
typedef void *(*dlsym_fn)(void *, const char *);
typedef void (*tmp_set_font_fn)(void *, void *, const void *);
typedef void *(*tmp_get_font_fn)(void *, const void *);
typedef void (*tmp_set_material_fn)(void *, void *, const void *);
typedef void (*tmp_set_text_fn)(void *, void *, const void *);
typedef void (*tmp_set_text_string_fn)(void *, void *, bool, const void *);
typedef void (*tmp_set_text_range_fn)(void *, void *, int32_t, int32_t, const void *);
typedef void (*game_object_set_active_fn)(void *, bool, const void *);
struct RuntimeColor {
    float r;
    float g;
    float b;
    float a;
};
typedef void (*tmp_set_color_fn)(void *, RuntimeColor, const void *);
typedef void (*material_set_color_fn)(void *, void *, RuntimeColor, const void *);
typedef void (*material_set_float_fn)(void *, void *, float, const void *);
typedef int32_t (*get_language_fn)(void *, const void *);
typedef void *(*class_get_parent_fn)(void *);
typedef const void *(*class_get_fields_fn)(void *, void **);
typedef const char *(*field_get_name_fn)(const void *);
typedef const void *(*field_get_type_fn)(const void *);
typedef void (*field_get_value_fn)(void *, const void *, void *);
typedef void (*field_set_value_fn)(void *, const void *, void *);

string_length_fn g_string_length = nullptr;
string_chars_fn g_string_chars = nullptr;
string_new_utf16_fn g_string_new_utf16 = nullptr;
array_addr_with_size_fn g_array_addr_with_size = nullptr;
gchandle_new_fn g_gchandle_new = nullptr;
gchandle_new_weakref_fn g_gchandle_new_weakref = nullptr;
gchandle_get_target_fn g_gchandle_get_target = nullptr;
gchandle_free_fn g_gchandle_free = nullptr;
create_dynamic_font_fn g_create_dynamic_font = nullptr;
create_tmp_font_fn g_create_tmp_font = nullptr;
runtime_invoke_fn g_runtime_invoke = nullptr;
object_get_class_fn g_object_get_class = nullptr;
object_unbox_fn g_object_unbox = nullptr;
class_get_method_global_fn g_class_get_method = nullptr;
tmp_set_font_fn g_tmp_set_font = nullptr;
tmp_get_font_fn g_tmp_get_font = nullptr;
tmp_set_material_fn g_original_tmp_set_shared_material = nullptr;
tmp_set_material_fn g_original_tmp_set_material = nullptr;
tmp_set_text_fn g_original_tmp_set_text = nullptr;
tmp_set_text_fn g_original_tmp_set_text_one = nullptr;
tmp_set_text_fn g_original_ui_set_text = nullptr;
tmp_set_text_fn g_original_text_mesh_set_text = nullptr;
tmp_set_text_fn g_original_tmp_set_text_chars = nullptr;
tmp_set_text_fn g_original_tmp_set_char_array = nullptr;
tmp_set_text_range_fn g_original_tmp_set_text_chars_range = nullptr;
tmp_set_text_range_fn g_original_tmp_set_char_array_range = nullptr;
tmp_set_text_string_fn g_original_tmp_set_text_string = nullptr;
game_object_set_active_fn g_original_game_object_set_active = nullptr;
thread_local bool g_refreshing_known_tmp_targets = false;
tmp_set_color_fn g_original_tmp_set_color = nullptr;
material_set_color_fn g_original_material_set_color = nullptr;
material_set_float_fn g_original_material_set_float = nullptr;
const void *g_create_dynamic_font_info = nullptr;
const void *g_create_tmp_font_info = nullptr;
const void *g_tmp_set_font_info = nullptr;
const void *g_tmp_get_font_info = nullptr;
const void *g_tmp_set_text_one_info = nullptr;
const void *g_get_fallback_fonts_info = nullptr;
const void *g_tmp_font_get_material_info = nullptr;
const void *g_tmp_font_get_materials_info = nullptr;
const void *g_tmp_font_has_character_info = nullptr;
const void *g_tmp_font_try_add_characters_info = nullptr;
const void *g_tmp_get_font_material_info = nullptr;
const void *g_tmp_set_font_material_info = nullptr;
const void *g_tmp_get_font_materials_info = nullptr;
const void *g_tmp_get_shared_material_info = nullptr;
const void *g_tmp_set_shared_material_info = nullptr;
const void *g_tmp_get_line_spacing_info = nullptr;
const void *g_tmp_set_line_spacing_info = nullptr;
const void *g_tmp_get_font_size_info = nullptr;
const void *g_tmp_set_font_size_info = nullptr;
const void *g_tmp_get_font_size_min_info = nullptr;
const void *g_tmp_set_font_size_min_info = nullptr;
const void *g_tmp_get_font_size_max_info = nullptr;
const void *g_tmp_set_font_size_max_info = nullptr;
const void *g_tmp_get_auto_sizing_info = nullptr;
const void *g_tmp_set_auto_sizing_info = nullptr;
const void *g_tmp_get_word_wrapping_info = nullptr;
const void *g_tmp_set_word_wrapping_info = nullptr;
const void *g_tmp_get_overflow_mode_info = nullptr;
const void *g_tmp_set_overflow_mode_info = nullptr;
const void *g_tmp_get_font_style_info = nullptr;
const void *g_tmp_set_font_style_info = nullptr;
const void *g_tmp_get_alpha_info = nullptr;
const void *g_tmp_set_alpha_info = nullptr;
const void *g_tmp_get_max_visible_info = nullptr;
const void *g_tmp_set_max_visible_info = nullptr;
const void *g_tmp_get_first_visible_info = nullptr;
const void *g_tmp_set_first_visible_info = nullptr;
const void *g_tmp_get_text_color_info = nullptr;
const void *g_tmp_set_text_color_info = nullptr;
const void *g_tmp_get_text_color_gradient_info = nullptr;
const void *g_tmp_set_text_color_gradient_info = nullptr;
const void *g_tmp_get_enable_vertex_gradient_info = nullptr;
const void *g_tmp_set_enable_vertex_gradient_info = nullptr;
const void *g_tmp_force_mesh_update_info = nullptr;
int32_t g_tmp_force_mesh_update_arg_count = 0;
const void *g_tmp_get_rect_transform_info = nullptr;
const void *g_ui_get_font_info = nullptr;
const void *g_ui_set_font_info = nullptr;
const void *g_ui_font_has_character_info = nullptr;
const void *g_ui_get_line_spacing_info = nullptr;
const void *g_ui_set_line_spacing_info = nullptr;
const void *g_ui_get_font_size_info = nullptr;
const void *g_ui_set_font_size_info = nullptr;
const void *g_ui_get_resize_min_size_info = nullptr;
const void *g_ui_set_resize_min_size_info = nullptr;
const void *g_ui_get_resize_max_size_info = nullptr;
const void *g_ui_set_resize_max_size_info = nullptr;
const void *g_ui_get_resize_best_fit_info = nullptr;
const void *g_ui_set_resize_best_fit_info = nullptr;
const void *g_ui_get_horizontal_overflow_info = nullptr;
const void *g_ui_set_horizontal_overflow_info = nullptr;
const void *g_ui_get_vertical_overflow_info = nullptr;
const void *g_ui_set_vertical_overflow_info = nullptr;
const void *g_ui_get_rect_transform_info = nullptr;
const void *g_rect_transform_get_rect_info = nullptr;
const void *g_object_get_name_info = nullptr;
const void *g_component_get_transform_info = nullptr;
const void *g_component_get_game_object_info = nullptr;
const void *g_game_object_get_active_in_hierarchy_info = nullptr;
const void *g_transform_get_parent_info = nullptr;
const void *g_tmp_get_text_info_info = nullptr;
const void *g_tmp_get_mesh_info = nullptr;
const void *g_tmp_get_text_value_info = nullptr;
const void *g_material_get_float_info = nullptr;
const void *g_material_set_float_info = nullptr;
const void *g_material_get_color_info = nullptr;
const void *g_material_set_color_info = nullptr;
const void *g_material_get_texture_info = nullptr;
const void *g_material_set_texture_info = nullptr;
const void *g_material_enable_keyword_info = nullptr;
const void *g_material_disable_keyword_info = nullptr;
const void *g_material_is_keyword_enabled_info = nullptr;
const void *g_unity_object_instantiate_info = nullptr;
il2cpp_init_fn g_original_il2cpp_init = nullptr;
dlsym_fn g_original_unity_dlsym = nullptr;
get_language_fn g_original_get_language = nullptr;
get_language_fn g_original_global_get_language = nullptr;
class_get_parent_fn g_class_get_parent = nullptr;
class_get_fields_fn g_class_get_fields = nullptr;
field_get_name_fn g_field_get_name = nullptr;
field_get_type_fn g_field_get_type = nullptr;
field_get_value_fn g_field_get_value = nullptr;
field_set_value_fn g_field_set_value = nullptr;
typedef char *(*type_get_name_owned_fn)(const void *);
typedef void (*il2cpp_free_owned_fn)(void *);
type_get_name_owned_fn g_type_get_name = nullptr;
il2cpp_free_owned_fn g_il2cpp_free = nullptr;
std::atomic<uint32_t> g_language_hook_hits{0};
std::atomic<uint64_t> g_acquisition_hits{0};
std::atomic<uint64_t> g_battle_hint_id_hits{0};
std::atomic<uint64_t> g_nested_list_hits{0};
std::atomic<uint64_t> g_tmp_primary_font_switches{0};
std::atomic<uint64_t> g_ui_primary_font_switches{0};
std::atomic<uint64_t> g_layout_decisions{0};
std::atomic<uint64_t> g_material_pin_failures{0};

/**
 * Keeps a managed wrapper strongly rooted for the duration of a native call.
 * TMP can rebuild arrays and trigger a GC between field lookups, so using the
 * raw il2cpp object address without a temporary strong handle is unsafe.
 */
class ManagedObjectPin {
public:
    explicit ManagedObjectPin(void *object) : object_(object) {
        if (object_ == nullptr || g_gchandle_new == nullptr ||
            g_gchandle_get_target == nullptr) {
            return;
        }
        handle_ = g_gchandle_new(object_, true);
    }

    ~ManagedObjectPin() {
        if (handle_ != 0 && g_gchandle_free != nullptr) {
            g_gchandle_free(handle_);
        }
    }

    bool alive() const {
        return object_ != nullptr && handle_ != 0 &&
                g_gchandle_get_target != nullptr &&
                g_gchandle_get_target(handle_) == object_;
    }

private:
    ManagedObjectPin(const ManagedObjectPin &) = delete;
    ManagedObjectPin &operator=(const ManagedObjectPin &) = delete;

    void *object_;
    uintptr_t handle_ = 0;
};

uintptr_t create_weak_object_handle(void *object) {
    return object == nullptr || g_gchandle_new_weakref == nullptr ?
            0 : g_gchandle_new_weakref(object, false);
}

void *resolve_live_object(uintptr_t handle, void *expected) {
    if (expected == nullptr || handle == 0 || g_gchandle_get_target == nullptr) {
        return nullptr;
    }
    void *target = g_gchandle_get_target(handle);
    return target == expected ? target : nullptr;
}

void release_object_handle(uintptr_t handle) {
    if (handle != 0 && g_gchandle_free != nullptr) {
        g_gchandle_free(handle);
    }
}

void register_source_material_owner(void *material, uintptr_t instance_handle) {
    if (material == nullptr || instance_handle == 0) return;
    pthread_mutex_lock(&g_font_state_lock);
    auto &owners = g_source_material_owners[material];
    if (std::find(owners.begin(), owners.end(), instance_handle) == owners.end()) {
        if (owners.size() >= 64) owners.erase(owners.begin());
        owners.push_back(instance_handle);
    }
    pthread_mutex_unlock(&g_font_state_lock);
}

bool has_source_material_owners(void *material) {
    if (material == nullptr) return false;
    pthread_mutex_lock(&g_font_state_lock);
    const bool registered =
            g_source_material_owners.find(material) != g_source_material_owners.end();
    pthread_mutex_unlock(&g_font_state_lock);
    return registered;
}

void request_tmp_rebuild(void *instance) {
    if (instance == nullptr) return;
    pthread_mutex_lock(&g_font_state_lock);
    if (g_pending_tmp_rebuilds.size() >= 512) g_pending_tmp_rebuilds.clear();
    g_pending_tmp_rebuilds.insert(instance);
    pthread_mutex_unlock(&g_font_state_lock);
}

bool consume_tmp_rebuild_request(void *instance) {
    if (instance == nullptr) return false;
    pthread_mutex_lock(&g_font_state_lock);
    bool requested = g_pending_tmp_rebuilds.erase(instance) != 0;
    pthread_mutex_unlock(&g_font_state_lock);
    return requested;
}

bool has_pending_tmp_rebuild(void *instance) {
    if (instance == nullptr) return false;
    pthread_mutex_lock(&g_font_state_lock);
    bool pending = g_pending_tmp_rebuilds.find(instance) != g_pending_tmp_rebuilds.end();
    pthread_mutex_unlock(&g_font_state_lock);
    return pending;
}

void prune_dead_tmp_component_states_locked() {
    if (g_original_tmp_states.size() < 1024 || g_gchandle_get_target == nullptr) {
        return;
    }
    for (auto entry = g_original_tmp_states.begin();
         entry != g_original_tmp_states.end();) {
        TmpComponentState &state = entry->second;
        if (state.instance_weak_handle != 0 &&
            g_gchandle_get_target(state.instance_weak_handle) == entry->first) {
            ++entry;
            continue;
        }
        release_object_handle(state.font_weak_handle);
        release_object_handle(state.shared_material_weak_handle);
        release_object_handle(state.source_material_weak_handle);
        release_object_handle(state.translated_material_weak_handle);
        release_object_handle(state.translated_material_font_weak_handle);
        release_object_handle(state.instance_weak_handle);
        entry = g_original_tmp_states.erase(entry);
    }
}

void prune_dead_ui_component_states_locked() {
    if (g_original_ui_states.size() < 1024 || g_gchandle_get_target == nullptr) {
        return;
    }
    for (auto entry = g_original_ui_states.begin();
         entry != g_original_ui_states.end();) {
        UiComponentState &state = entry->second;
        if (state.instance_weak_handle != 0 &&
            g_gchandle_get_target(state.instance_weak_handle) == entry->first) {
            ++entry;
            continue;
        }
        release_object_handle(state.font_weak_handle);
        release_object_handle(state.instance_weak_handle);
        entry = g_original_ui_states.erase(entry);
    }
}

bool append_utf8(uint32_t codepoint, std::string *output) {
    if (codepoint <= 0x7f) {
        output->push_back(static_cast<char>(codepoint));
    } else if (codepoint <= 0x7ff) {
        output->push_back(static_cast<char>(0xc0 | (codepoint >> 6)));
        output->push_back(static_cast<char>(0x80 | (codepoint & 0x3f)));
    } else if (codepoint <= 0xffff) {
        output->push_back(static_cast<char>(0xe0 | (codepoint >> 12)));
        output->push_back(static_cast<char>(0x80 | ((codepoint >> 6) & 0x3f)));
        output->push_back(static_cast<char>(0x80 | (codepoint & 0x3f)));
    } else if (codepoint <= 0x10ffff) {
        output->push_back(static_cast<char>(0xf0 | (codepoint >> 18)));
        output->push_back(static_cast<char>(0x80 | ((codepoint >> 12) & 0x3f)));
        output->push_back(static_cast<char>(0x80 | ((codepoint >> 6) & 0x3f)));
        output->push_back(static_cast<char>(0x80 | (codepoint & 0x3f)));
    } else {
        return false;
    }
    return true;
}

bool utf16_to_utf8(const uint16_t *input, int32_t length, std::string *output) {
    if (input == nullptr || length < 0) return false;
    output->clear();
    output->reserve(static_cast<size_t>(length));
    for (int32_t i = 0; i < length; ++i) {
        uint32_t codepoint = input[i];
        if (codepoint >= 0xd800 && codepoint <= 0xdbff) {
            if (++i >= length || input[i] < 0xdc00 || input[i] > 0xdfff) return false;
            codepoint = 0x10000 + ((codepoint - 0xd800) << 10) + (input[i] - 0xdc00);
        } else if (codepoint >= 0xdc00 && codepoint <= 0xdfff) {
            return false;
        }
        if (!append_utf8(codepoint, output)) return false;
    }
    return true;
}

bool utf8_to_utf16(const std::string &input, std::u16string *output) {
    output->clear();
    for (size_t i = 0; i < input.size();) {
        unsigned char first = static_cast<unsigned char>(input[i++]);
        uint32_t codepoint = 0;
        int continuation = 0;
        if (first <= 0x7f) codepoint = first;
        else if ((first & 0xe0) == 0xc0) { codepoint = first & 0x1f; continuation = 1; }
        else if ((first & 0xf0) == 0xe0) { codepoint = first & 0x0f; continuation = 2; }
        else if ((first & 0xf8) == 0xf0) { codepoint = first & 0x07; continuation = 3; }
        else return false;
        if (i + continuation > input.size()) return false;
        for (int j = 0; j < continuation; ++j) {
            unsigned char next = static_cast<unsigned char>(input[i++]);
            if ((next & 0xc0) != 0x80) return false;
            codepoint = (codepoint << 6) | (next & 0x3f);
        }
        if ((continuation == 1 && codepoint < 0x80) ||
            (continuation == 2 && codepoint < 0x800) ||
            (continuation == 3 && codepoint < 0x10000) ||
            codepoint > 0x10ffff || (codepoint >= 0xd800 && codepoint <= 0xdfff)) return false;
        if (codepoint <= 0xffff) {
            output->push_back(static_cast<char16_t>(codepoint));
        } else {
            codepoint -= 0x10000;
            output->push_back(static_cast<char16_t>(0xd800 + (codepoint >> 10)));
            output->push_back(static_cast<char16_t>(0xdc00 + (codepoint & 0x3ff)));
        }
    }
    return true;
}

bool is_usable_display_text(const std::string &value) {
    std::u16string utf16;
    if (value.empty() || !utf8_to_utf16(value, &utf16)) return false;
    for (char16_t character : utf16) {
        // U+FFFD 与非法控制字符都说明资源在进入索引前已经损坏。
        // 保持日文原文比继续渲染方框、不可见控制符或乱码更安全。
        if (character == 0xfffd ||
            (character < 0x20 && character != u'\n' && character != u'\r' &&
             character != u'\t') ||
            (character >= 0x7f && character <= 0x9f)) {
            return false;
        }
    }
    return true;
}

bool contains_japanese_kana(const std::string &value) {
    // link/ruby 等属性可能保留日文键；控制标签中的假名不能判定可见正文仍未翻译。
    std::string visible;
    visible.reserve(value.size());
    for (size_t offset = 0; offset < value.size();) {
        if (value[offset] == '<') {
            size_t end = value.find('>', offset + 1);
            size_t name = offset + 1;
            if (name < value.size() && value[name] == '/') ++name;
            size_t name_end = name;
            while (name_end < value.size() &&
                   ((value[name_end] >= 'a' && value[name_end] <= 'z') ||
                    (value[name_end] >= 'A' && value[name_end] <= 'Z') || value[name_end] == '-')) ++name_end;
            // 但丁对白可能用 <えっと…> 包句子，不能把这类正文当成 TMP 属性丢掉。
            bool control_name = name_end > name && name_end < value.size() &&
                    (value[name_end] == '=' || value[name_end] == '>' || value[name_end] == ' ' ||
                     value[name_end] == '/' || value[name_end] == '\t');
            if (end != std::string::npos && control_name) {
                offset = end + 1;
                continue;
            }
        }
        visible.push_back(value[offset++]);
    }
    std::u16string utf16;
    if (!utf8_to_utf16(visible, &utf16)) return false;
    return std::any_of(utf16.begin(), utf16.end(), [](char16_t character) {
        // 覆盖平假名、片假名、片假名扩展与半角片假名。日文汉字与中文共用码位，
        // 不能单凭汉字判定，否则会误伤“振動 -> 震颤”一类合法术语。
        return (character >= 0x3040 && character <= 0x30ff) ||
               (character >= 0x31f0 && character <= 0x31ff) ||
               (character >= 0xff66 && character <= 0xff9f);
    });
}

void *new_managed_string(const std::string &value) {
    std::u16string utf16;
    if (g_string_new_utf16 == nullptr || !utf8_to_utf16(value, &utf16)) return nullptr;
    return g_string_new_utf16(reinterpret_cast<const uint16_t *>(utf16.data()),
                              static_cast<int32_t>(utf16.size()));
}

/**
 * Repairs a localization tag that the current game build resolves to the
 * generic enum fallback "Unknown" before the translated text reaches TMP.
 * Keep the repair context-specific so legitimate English "Unknown" text is
 * not changed.
 */
bool repair_unresolved_localization_tags(
        const std::string &source, std::string *output) {
    if (output == nullptr) return false;
    const std::string unresolved = "Unknown效果";
    const std::string translated = "[使用前]效果";
    size_t offset = 0;
    output->clear();
    output->reserve(source.size());
    while (offset < source.size()) {
        const size_t match = source.find(unresolved, offset);
        if (match == std::string::npos) {
            output->append(source, offset, std::string::npos);
            break;
        }
        output->append(source, offset, match - offset);
        output->append(translated);
        offset = match + unresolved.size();
    }
    return *output != source;
}

/**
 * The current Japanese localization data used by the container does not map
 * the translated [使用前] skill-tag key. Translate this one passive's token
 * before the game formatter consumes it; leaving square brackets would make
 * TextData_SkillTag perform the missing lookup again and emit "Unknown".
 */
bool repair_translated_skill_tag_placeholders(std::string *translation) {
    if (translation == nullptr ||
        translation->find("店内广播") == std::string::npos) {
        return false;
    }
    const std::string unresolved = "[使用前]";
    const std::string readable = "使用前";
    bool changed = false;
    size_t offset = 0;
    while ((offset = translation->find(unresolved, offset)) != std::string::npos) {
        translation->replace(offset, unresolved.size(), readable);
        offset += readable.size();
        changed = true;
    }
    return changed;
}

void rebuild_term_trie() {
    g_term_trie.clear();
    g_term_trie.emplace_back();
    for (const auto &entry : g_terms) {
        // 二进制与编译器都拦截非幂等扩展词，不能依赖邻域文本碰巧包含完整译文。
        if (entry.first != entry.second && entry.second.find(entry.first) != std::string::npos) continue;
        if (!is_usable_display_text(entry.first) ||
            !is_usable_display_text(entry.second)) {
            continue;
        }
        size_t node = 0;
        for (unsigned char byte : entry.first) {
            auto child = g_term_trie[node].children.find(byte);
            if (child == g_term_trie[node].children.end()) {
                size_t next = g_term_trie.size();
                g_term_trie[node].children[byte] = next;
                g_term_trie.emplace_back();
                node = next;
            } else {
                node = child->second;
            }
        }
        g_term_trie[node].source = &entry.first;
        g_term_trie[node].translation = &entry.second;
    }
}

/**
 * Skips invisible Unicode separators that a formatter may inject inside a
 * localized term for line-breaking or bidirectional text handling.
 *
 * @param source UTF-8 display string.
 * @param offset Byte offset at or after the candidate separator.
 * @return First byte after the separator, or the unchanged offset.
 */
size_t invisible_term_separator_end(const std::string &source, size_t offset) {
    if (offset >= source.size()) return offset;
    const unsigned char first = static_cast<unsigned char>(source[offset]);
    if (first == 0xc2 && offset + 1 < source.size() &&
        static_cast<unsigned char>(source[offset + 1]) == 0xad) {
        return offset + 2;  // U+00AD SOFT HYPHEN
    }
    if (first == 0xc2 && offset + 1 < source.size() &&
        static_cast<unsigned char>(source[offset + 1]) == 0x85) {
        return offset + 2;  // U+0085 NEXT LINE
    }
    if (first == 0xd8 && offset + 1 < source.size() &&
        static_cast<unsigned char>(source[offset + 1]) == 0x9c) {
        return offset + 2;  // U+061C ARABIC LETTER MARK
    }
    if (first == 0xe1 && offset + 2 < source.size() &&
        static_cast<unsigned char>(source[offset + 1]) == 0xa0 &&
        static_cast<unsigned char>(source[offset + 2]) == 0x8e) {
        return offset + 3;  // U+180E MONGOLIAN VOWEL SEPARATOR
    }
    if (first == 0xe2 && offset + 2 < source.size()) {
        const unsigned char second = static_cast<unsigned char>(source[offset + 1]);
        const unsigned char third = static_cast<unsigned char>(source[offset + 2]);
        if (second == 0x80 && (third == 0x8b || third == 0x8c || third == 0x8d ||
                               third == 0x8e || third == 0x8f)) {
            return offset + 3;  // ZWSP, ZWNJ, ZWJ and LRM/RLM
        }
        if (second == 0x80 && (third == 0xa8 || third == 0xa9)) {
            return offset + 3;  // U+2028 LINE SEPARATOR / U+2029 PARAGRAPH SEPARATOR
        }
        if (second == 0x80 && third >= 0xaa && third <= 0xae) {
            return offset + 3;  // U+202A..U+202E bidirectional controls
        }
        if (second == 0x80 && third == 0xaf) {
            return offset + 3;  // U+202F NARROW NO-BREAK SPACE
        }
        if (second == 0x81 && third >= 0xa0 && third <= 0xa4) {
            return offset + 3;  // U+2060..U+2064 invisible operators
        }
        if (second == 0x81 && third >= 0xa6 && third <= 0xaf) {
            return offset + 3;  // U+2066..U+206F isolate controls
        }
    }
    if (first == 0xef && offset + 2 < source.size() &&
        static_cast<unsigned char>(source[offset + 1]) == 0xbb &&
        static_cast<unsigned char>(source[offset + 2]) == 0xbf) {
        return offset + 3;  // U+FEFF ZERO WIDTH NO-BREAK SPACE
    }
    return offset;
}

/**
 * Skips whitespace that the formatter may leave between characters of a
 * glossary name. The index compiler emits compact aliases for these names,
 * so the trie must traverse those aliases without copying formatting
 * whitespace into the translated term.
 */
size_t internal_term_separator_end(const std::string &source, size_t offset) {
    const size_t invisible_end = invisible_term_separator_end(source, offset);
    if (invisible_end != offset || offset >= source.size()) return invisible_end;

    const unsigned char first = static_cast<unsigned char>(source[offset]);
    if (first == ' ' || first == '\t' || first == '\n' || first == '\r') {
        return offset + 1;
    }
    if (first == 0xc2 && offset + 1 < source.size()) {
        const unsigned char second = static_cast<unsigned char>(source[offset + 1]);
        if (second == 0xa0) return offset + 2;  // U+00A0 NO-BREAK SPACE
    }
    if (first == 0xe1 && offset + 2 < source.size() &&
        static_cast<unsigned char>(source[offset + 1]) == 0x9a &&
        static_cast<unsigned char>(source[offset + 2]) == 0x80) {
        return offset + 3;  // U+1680 OGHAM SPACE MARK
    }
    if (first == 0xe2 && offset + 2 < source.size() &&
        static_cast<unsigned char>(source[offset + 1]) == 0x80) {
        const unsigned char third = static_cast<unsigned char>(source[offset + 2]);
        if (third >= 0x80 && third <= 0x8a) {
            return offset + 3;  // U+2000..U+200A Unicode spaces
        }
    }
    if (first == 0xe3 && offset + 2 < source.size() &&
        static_cast<unsigned char>(source[offset + 1]) == 0x80 &&
        static_cast<unsigned char>(source[offset + 2]) == 0x80) {
        return offset + 3;  // U+3000 IDEOGRAPHIC SPACE
    }
    return offset;
}

bool translate_embedded_terms(
        const std::string &source, std::string *output,
        const std::vector<limbus_translation_guard::ProtectedSpan> *protected_spans = nullptr) {
    if (g_term_trie.size() <= 1) return false;
    output->clear();
    output->reserve(source.size());
    bool changed = false;
    size_t protected_index = 0;
    for (size_t offset = 0; offset < source.size();) {
        const size_t separator_end = invisible_term_separator_end(source, offset);
        if (separator_end != offset) {
            offset = separator_end;
            continue;
        }
        // 这些区间来自本次 formatter 已汉化的字段模板，参数区域仍继续查词。
        if (protected_spans != nullptr) {
            while (protected_index < protected_spans->size() &&
                   (*protected_spans)[protected_index].end <= offset) ++protected_index;
            if (protected_index < protected_spans->size()) {
                const auto &span = (*protected_spans)[protected_index];
                if (span.begin <= offset && offset < span.end) {
                    output->append(source, offset, span.end - offset);
                    offset = span.end;
                    continue;
                }
            }
        }
        // TMP rich-text markup is control data, not display text.  In
        // particular, an ASCII term such as "or" must never rewrite the tail
        // of <color> into <col或>, which makes TMP display the broken tag.
        if (source[offset] == '<') {
            size_t tag_end = source.find('>', offset + 1);
            if (tag_end != std::string::npos) {
                output->append(source, offset, tag_end - offset + 1);
                offset = tag_end + 1;
                continue;
            }
        }
        size_t node = 0;
        size_t best_end = offset;
        const std::string *best_source = nullptr;
        const std::string *best = nullptr;
        for (size_t cursor = offset; cursor < source.size();) {
            const size_t inner_separator_end =
                    node == 0 ? invisible_term_separator_end(source, cursor) :
                    internal_term_separator_end(source, cursor);
            if (inner_separator_end != cursor) {
                cursor = inner_separator_end;
                continue;
            }
            if (protected_spans != nullptr && protected_index < protected_spans->size() &&
                cursor >= (*protected_spans)[protected_index].begin) break;
            unsigned char byte = static_cast<unsigned char>(source[cursor]);
            auto child = g_term_trie[node].children.find(byte);
            if (child == g_term_trie[node].children.end()) break;
            node = child->second;
            if (g_term_trie[node].translation != nullptr) {
                best_source = g_term_trie[node].source;
                best = g_term_trie[node].translation;
                best_end = cursor + 1;
            }
            ++cursor;
        }
        if (best != nullptr) {
            bool ascii_word = true;
            for (size_t index = offset; index < best_end; ++index) {
                unsigned char byte = static_cast<unsigned char>(source[index]);
                // 带方括号或标点的英文术语仍是 ASCII，不能让 [Binding]2 命中 [Binding]20。
                if (byte >= 0x80) {
                    ascii_word = false;
                    break;
                }
            }
            auto is_ascii_word_byte = [](unsigned char byte) {
                return (byte >= '0' && byte <= '9') ||
                       (byte >= 'A' && byte <= 'Z') ||
                       (byte >= 'a' && byte <= 'z') || byte == '_';
            };
            if (ascii_word &&
                ((offset > 0 && is_ascii_word_byte(
                        static_cast<unsigned char>(source[offset - 1]))) ||
                 (best_end < source.size() && is_ascii_word_byte(
                        static_cast<unsigned char>(source[best_end]))))) {
                best = nullptr;
            }
        }
        if (best != nullptr) {
            output->append(*best);
            offset = best_end;
            changed = changed || *best != *best_source;
        } else {
            output->push_back(source[offset++]);
        }
    }
    // 短术语只作为格式化后文本的补充通道。若替换后仍残留假名，说明当前文本
    // 只命中了局部词条；拒绝输出半中文半日文的碎片，等待完整映射或后续词表覆盖。
    if (changed && contains_japanese_kana(source) && contains_japanese_kana(*output)) {
        output->clear();
        return false;
    }
    return changed;
}

bool strip_line_height_tags(const std::string &source, std::string *output) {
    output->clear();
    output->reserve(source.size());
    bool changed = false;
    for (size_t offset = 0; offset < source.size();) {
        if (source.compare(offset, 13, "<line-height=") == 0) {
            size_t end = source.find('>', offset + 13);
            if (end != std::string::npos) {
                const std::string tag = source.substr(offset, end - offset + 1);
                if (tag != "<line-height=170%>" && tag != "<line-height=100%>") {
                    // 混入其他布局时拒绝本次归一化，保留整段原始结构供常规匹配处理。
                    *output = source;
                    return false;
                }
                offset = end + 1;
                changed = true;
                continue;
            }
        }
        output->push_back(source[offset++]);
    }
    return changed;
}

std::string apply_dante_line_height(const std::string &translation) {
    std::string output;
    output.reserve(translation.size() + 64);
    for (size_t offset = 0; offset < translation.size(); ++offset) {
        if (translation[offset] != '\n') {
            output.push_back(translation[offset]);
            continue;
        }
        output += "<line-height=170%>\n";
        if (offset + 1 < translation.size() && translation[offset + 1] == '-') {
            output.push_back('-');
            output += "<line-height=100%>";
            ++offset;
        } else {
            output += "<line-height=100%>";
        }
    }
    return output;
}

std::string apply_uniform_line_height(const std::string &text, int32_t percentage) {
    std::string output;
    output.reserve(text.size() + 96);
    std::string line_height_tag = "<line-height=" + std::to_string(percentage) + "%>\n";
    for (char character : text) {
        if (character != '\n') {
            output.push_back(character);
            continue;
        }
        // TMP 在换行符前读取当前 line-height；换行后立即恢复 100%，避免标签
        // 继续影响同一组件之外的自动布局计算。
        output += line_height_tag;
        output += "<line-height=100%>";
    }
    return output;
}

float personality_title_character_width(uint32_t codepoint) {
    // 拉丁字母、数字与标点在当前中文字体中的实际宽度明显小于一个汉字；
    // 用近似字宽而不是字节数计算，避免把 LCA、E.G.O 等缩写过度拆行。
    if (codepoint == ' ') return 0.35f;
    if (codepoint <= 0x7f) return 0.55f;
    return 1.0f;
}

std::vector<std::string> split_utf8_codepoints(const std::string &text) {
    std::vector<std::string> codepoints;
    for (size_t offset = 0; offset < text.size();) {
        unsigned char lead = static_cast<unsigned char>(text[offset]);
        size_t length = 1;
        if ((lead & 0xe0) == 0xc0) length = 2;
        else if ((lead & 0xf0) == 0xe0) length = 3;
        else if ((lead & 0xf8) == 0xf0) length = 4;
        if (offset + length > text.size()) length = 1;
        codepoints.emplace_back(text.substr(offset, length));
        offset += length;
    }
    return codepoints;
}

uint32_t first_utf8_codepoint(const std::string &text) {
    if (text.empty()) return 0;
    const unsigned char *bytes = reinterpret_cast<const unsigned char *>(text.data());
    if ((bytes[0] & 0x80) == 0) return bytes[0];
    if ((bytes[0] & 0xe0) == 0xc0 && text.size() >= 2) {
        return ((bytes[0] & 0x1f) << 6) | (bytes[1] & 0x3f);
    }
    if ((bytes[0] & 0xf0) == 0xe0 && text.size() >= 3) {
        return ((bytes[0] & 0x0f) << 12) | ((bytes[1] & 0x3f) << 6) |
               (bytes[2] & 0x3f);
    }
    if ((bytes[0] & 0xf8) == 0xf0 && text.size() >= 4) {
        return ((bytes[0] & 0x07) << 18) | ((bytes[1] & 0x3f) << 12) |
               ((bytes[2] & 0x3f) << 6) | (bytes[3] & 0x3f);
    }
    return bytes[0];
}

std::vector<std::string> wrap_personality_title_line(const std::string &line,
                                                     float maximum_width) {
    float total_width = 0.0f;
    for (const std::string &codepoint : split_utf8_codepoints(line)) {
        total_width += personality_title_character_width(first_utf8_codepoint(codepoint));
    }
    // 汉化包分行拥有最高优先级：未超宽的原始行原样保留；超宽但没有空格的行
    // 也不按汉字硬拆，避免运行时自行改变译者确定的名称结构。
    if (total_width <= maximum_width || line.find(' ') == std::string::npos) {
        return {line};
    }
    std::vector<std::string> result;
    std::vector<std::string> codepoints = split_utf8_codepoints(line);
    size_t start = 0;
    while (start < codepoints.size()) {
        float width = 0.0f;
        size_t end = start;
        size_t last_space = std::string::npos;
        while (end < codepoints.size()) {
            uint32_t codepoint = first_utf8_codepoint(codepoints[end]);
            float next_width = personality_title_character_width(codepoint);
            if (end > start && width + next_width > maximum_width) break;
            width += next_width;
            if (codepoint == ' ') last_space = end;
            ++end;
        }
        if (end < codepoints.size() && last_space != std::string::npos && last_space > start) {
            end = last_space;
        } else if (end < codepoints.size()) {
            // 当前剩余片段找不到可用空格时保留整段，不在汉字中间制造新换行。
            end = codepoints.size();
        }
        std::string wrapped;
        for (size_t index = start; index < end; ++index) wrapped += codepoints[index];
        while (!wrapped.empty() && wrapped.back() == ' ') wrapped.pop_back();
        if (!wrapped.empty()) result.push_back(wrapped);
        start = end;
        while (start < codepoints.size() && codepoints[start] == " ") ++start;
    }
    if (result.empty()) result.push_back(line);
    return result;
}

std::string reflow_personality_title(const std::string &text, size_t *line_count) {
    std::vector<std::string> lines;
    size_t start = 0;
    while (start <= text.size()) {
        size_t end = text.find('\n', start);
        std::string line = text.substr(start, end == std::string::npos ?
                std::string::npos : end - start);
        // 230 宽、35 字号的人格卡可稳定容纳约 8.4 个等宽汉字；优先在包内空格处断行。
        std::vector<std::string> wrapped = wrap_personality_title_line(line, 8.4f);
        lines.insert(lines.end(), wrapped.begin(), wrapped.end());
        if (end == std::string::npos) break;
        start = end + 1;
    }
    if (line_count != nullptr) *line_count = lines.size();
    std::string output;
    for (size_t index = 0; index < lines.size(); ++index) {
        if (index > 0) output.push_back('\n');
        output += lines[index];
    }
    return output;
}

/** 为当前 formatter 保存已汉化字段模板，离开调用栈即丢弃，避免跨页面内容碰撞。 */
struct FormatterTranslationScope;
bool is_translation_owned_string(void *text);
thread_local FormatterTranslationScope *g_formatter_translation_scope = nullptr;
struct FormatterTranslationScope {
    FormatterTranslationScope *previous = g_formatter_translation_scope;
    std::vector<std::string> templates;
    std::string result;
    size_t bytes = 0;
    bool truncated = false;
    bool result_incomplete = false;

    // 每层 formatter 独立收集字段，嵌套返回后恢复上层，不共享其他线程的参数。
    FormatterTranslationScope() { g_formatter_translation_scope = this; }
    ~FormatterTranslationScope();
};

/** 仅记录接管层已经产生的字段值，供同一 formatter 的字面区间保护使用。 */
void remember_formatter_translation(const std::string &text) {
    auto *scope = g_formatter_translation_scope;
    if (scope == nullptr || text.empty()) return;
    if (text.size() > 64 * 1024 || scope->templates.size() >= 256 ||
        scope->bytes + text.size() > 256 * 1024) {
        scope->truncated = true;
        return;
    }
    if (std::find(scope->templates.begin(), scope->templates.end(), text) == scope->templates.end()) {
        scope->templates.push_back(text);
        scope->bytes += text.size();
    }
}

FormatterTranslationScope::~FormatterTranslationScope() {
    g_formatter_translation_scope = previous;
    // 只把子 formatter 的最终返回值交给上层参数，不泄漏子对象的其他私有字段模板。
    if (!result.empty()) remember_formatter_translation(result);
    if (result_incomplete && previous != nullptr) previous->truncated = true;
}

/** 记录本层最终返回给上层的已译值，参数复制成新字符串后仍能在同一调用链保留来源。 */
void remember_formatter_result(FormatterTranslationScope *scope, void *text) {
    if (scope == nullptr || !is_translation_owned_string(text)) return;
    int32_t length = g_string_length(text);
    if (length >= 0 && length <= 65536) {
        utf16_to_utf8(g_string_chars(text), length, &scope->result);
    } else {
        scope->result_incomplete = true;
    }
}

/** 检查弱句柄当前仍指向同一个活对象，旧地址被 GC 复用时不能跳过新的日文字符串。 */
bool is_translation_owned_string(void *text) {
    if (text == nullptr || g_gchandle_get_target == nullptr) return false;
    pthread_mutex_lock(&g_font_state_lock);
    auto found = g_translated_string_origins.find(text);
    bool owned = found != g_translated_string_origins.end() &&
            g_gchandle_get_target(found->second) == text;
    if (found != g_translated_string_origins.end() && !owned) g_translated_string_origins.erase(found);
    pthread_mutex_unlock(&g_font_state_lock);
    return owned;
}

/** 登记新建译文的弱来源；只回收失效句柄，所有凭据存活并满额时拒绝发布新译文。 */
bool remember_translation_owned_string(void *text, size_t capacity = kMaxTranslatedOrigins) {
    if (text == nullptr || g_gchandle_new_weakref == nullptr ||
        g_gchandle_get_target == nullptr || g_gchandle_free == nullptr) return false;
    uintptr_t handle = g_gchandle_new_weakref(text, false);
    if (handle == 0) return false;
    pthread_mutex_lock(&g_font_state_lock);
    // 每次增量扫描少量弱凭据；只回收已被 GC 收走的对象，不淘汰仍存活的原地汉化字段。
    size_t scans = std::min<size_t>(32, g_translated_origin_slots.size());
    if (g_translated_origin_slots.size() >= capacity && g_free_translated_origin_slots.empty()) {
        scans = g_translated_origin_slots.size();
    }
    for (size_t index = 0; index < scans && !g_translated_origin_slots.empty(); ++index) {
        size_t position = g_translated_origin_cursor++ % g_translated_origin_slots.size();
        auto &candidate = g_translated_origin_slots[position];
        if (candidate.handle == 0 || g_gchandle_get_target(candidate.handle) != nullptr) continue;
        auto old = g_translated_string_origins.find(candidate.object);
        if (old != g_translated_string_origins.end() && old->second == candidate.handle) {
            g_translated_string_origins.erase(old);
        }
        g_gchandle_free(candidate.handle);
        candidate.object = nullptr;
        candidate.handle = 0;
        g_free_translated_origin_slots.push_back(position);
    }
    size_t position = 0;
    if (!g_free_translated_origin_slots.empty()) {
        position = g_free_translated_origin_slots.back();
        g_free_translated_origin_slots.pop_back();
    } else if (g_translated_origin_slots.size() < capacity) {
        position = g_translated_origin_slots.size();
        g_translated_origin_slots.emplace_back();
    } else {
        // 所有凭据都存活且达到固定上限时，宁可保留本次原文，也不能发布失去来源的中文。
        g_gchandle_free(handle);
        pthread_mutex_unlock(&g_font_state_lock);
        return false;
    }
    auto &slot = g_translated_origin_slots[position];
    // 容器 native 使用 C++11；带默认成员值的类型不能按聚合列表赋值。
    slot.object = text;
    slot.handle = handle;
    g_translated_string_origins[text] = handle;
    pthread_mutex_unlock(&g_font_state_lock);
    return true;
}

/** 独立审计隔离每条探针的来源状态；生产中的弱缓存自然按上限淘汰。 */
void clear_translation_owned_strings() {
    pthread_mutex_lock(&g_font_state_lock);
    for (auto &slot : g_translated_origin_slots) {
        if (slot.handle != 0 && g_gchandle_free != nullptr) g_gchandle_free(slot.handle);
        slot.object = nullptr;
        slot.handle = 0;
    }
    g_translated_origin_cursor = 0;
    g_translated_origin_slots.clear();
    g_free_translated_origin_slots.clear();
    g_translated_string_origins.clear();
    pthread_mutex_unlock(&g_font_state_lock);
}

/** Identifies the small set of user-reported strings without logging general UI text. */
const char *known_text_probe_kind(const std::string &source) {
    if (source ==
        "E.G.Oに侵蝕された囚人は、敵味方を区別せずに攻撃しますのでご注意ください。" ||
        source == "被E.G.O侵蚀的罪人会无差别地进行攻击，请多加留意。") {
        return "battle-hint-id-7";
    }
    if (source == "探査終了報酬" || source == "探索结束奖励") {
        return "mirror-finished-title";
    }
    if (source == "ウィークリー追加報酬" || source == "每周加成") {
        return "mirror-weekly-bonus";
    }
    if (source == "受け取り拒否" || source == "放弃奖励") {
        return "mirror-give-up";
    }
    if (source == "管理人経験値" || source == "经理经验值") {
        return "mirror-manager-exp";
    }
    if (source == "受け取り" || source == "受取" || source == "领取") {
        return "mirror-claim";
    }
    return nullptr;
}

bool is_mirror_dungeon_reward_control(
        const std::string &component_name,
        const std::string &parent_name,
        const std::string &text = std::string()) {
    if ((component_name == "TilteText" && parent_name == "Title") ||
        (component_name == "[Text]AdditionalRewardLabel" && parent_name == "[Rect]Upper") ||
        (component_name == "[Text]Text" && parent_name == "[Button]Cancel") ||
        (component_name == "[Text]Text" && (parent_name == "[Button]Ok" || parent_name == "[Button]Confirm" || parent_name == "[Button]Receive" || parent_name == "[Button]Claim")) ||
        (component_name == "[Text]Label" && parent_name == "[Rect]Exp") ||
        component_name == "[Text]AdditionalRewardLabel") {
        return true;
    }
    if (parent_name.find("MirrorDungeon") != std::string::npos ||
        parent_name.find("DungeonReward") != std::string::npos ||
        parent_name.find("ClearResult") != std::string::npos ||
        parent_name.find("Cancel") != std::string::npos ||
        parent_name.find("Upper") != std::string::npos ||
        parent_name.find("Exp") != std::string::npos ||
        component_name.find("AdditionalReward") != std::string::npos ||
        component_name.find("ClearReward") != std::string::npos) {
        return true;
    }
    if (!text.empty()) {
        static const char *const keywords[] = {
                "探査終了報酬", "探索结束奖励",
                "ウィークリー追加報酬", "每周加成",
                "受け取り拒否", "放弃奖励",
                "受け取り", "受取", "领取",
                "管理人経験値", "经理经验值",
                "投影度",
                "追加報酬", "额外奖励",
                "クリア報酬", "通关奖励",
                "困難難易度", "困难难度",
                "ガラス窓", "玻璃窗", "硝子窓"
        };
        for (const char *kw : keywords) {
            if (text.find(kw) != std::string::npos) return true;
        }
    }
    return false;
}

bool is_tracked_mirror_result_control(
        const char *kind, const std::string &component_name,
        const std::string &parent_name) {
    if (kind == nullptr) return false;
    const std::string label(kind);
    if (label == "mirror-finished-title") {
        return component_name == "TilteText" && parent_name == "Title";
    }
    if (label == "mirror-weekly-bonus") {
        return component_name == "[Text]AdditionalRewardLabel" &&
                parent_name == "[Rect]Upper";
    }
    if (label == "mirror-give-up") {
        return component_name == "[Text]Text" &&
                parent_name == "[Button]Cancel";
    }
    if (label == "mirror-manager-exp") {
        return component_name == "[Text]Label" &&
                parent_name == "[Rect]Exp";
    }
    if (label == "mirror-claim") {
        return (component_name == "[Text]Text" && (parent_name == "[Button]Ok" || parent_name == "[Button]Confirm" || parent_name == "[Button]Receive" || parent_name == "[Button]Claim")) ||
               parent_name.find("Ok") != std::string::npos ||
               parent_name.find("Claim") != std::string::npos;
    }
    return false;
}

void log_known_text_probe(
        const char *kind, const char *stage, const std::string &source,
        const std::string &result, bool translated, bool incomplete,
        size_t template_count) {
    if (kind == nullptr || stage == nullptr) return;
    static std::atomic<uint64_t> probes{0};
    uint64_t hit = probes.fetch_add(1, std::memory_order_relaxed) + 1;
    if (hit > 160) return;
    LT_LOGI("Known text probe kind=%s stage=%s translated=%d incomplete=%d"
            " templates=%zu sourceChars=%zu resultChars=%zu source=%s result=%s",
            kind, stage, translated ? 1 : 0, incomplete ? 1 : 0,
            template_count, source.size(), result.size(),
            source.c_str(), result.c_str());
}

bool translate_exact_ui_text(const std::string &source, std::string *translation);

/**
 * Translates a formatter-composed value line by line when every non-empty line
 * has its own authoritative full-text translation. This covers labels joined to
 * descriptions with newlines without allowing short-term substitutions inside
 * arbitrary sentences.
 */
std::string replace_all_substrings(const std::string &input,
                                   const std::string &needle,
                                   const std::string &replacement) {
    if (needle.empty()) return input;
    std::string output;
    output.reserve(input.size());
    size_t start = 0;
    while (true) {
        size_t pos = input.find(needle, start);
        if (pos == std::string::npos) {
            output.append(input, start, std::string::npos);
            break;
        }
        output.append(input, start, pos - start);
        output.append(replacement);
        start = pos + needle.size();
    }
    return output;
}

bool lookup_template_translation(const std::string &template_candidate,
                                 const std::string &zh_sinner,
                                 std::string *translated) {
    auto exact = g_index.find(template_candidate);
    if (exact != g_index.end()) {
        *translated = replace_all_substrings(exact->second, "{0}", zh_sinner);
        return true;
    }
    auto fallback = g_fallback_index.find(template_candidate);
    if (fallback != g_fallback_index.end()) {
        *translated = replace_all_substrings(fallback->second, "{0}", zh_sinner);
        return true;
    }
    return false;
}

bool translate_sinner_formatted_text(const std::string &source,
                                     std::string *translated) {
    if (translated == nullptr || source.empty()) return false;
    struct SinnerNamePair {
        const char *jp;
        const char *zh;
    };
    static const SinnerNamePair kSinnerNames[] = {
            {"ドンキホーテ", "堂吉诃德"},
            {"ヒースクリフ", "希斯克利夫"},
            {"イシュメール", "以实玛利"},
            {"ジア・シーチュン", "贾惜春"},
            {"ファウスト", "浮士德"},
            {"シンクレア", "辛克莱"},
            {"ウーティス", "奥提斯"},
            {"グレゴール", "格里高尔"},
            {"ムルソー", "默尔索"},
            {"ロージャ", "罗佳"},
            {"イサン", "李箱"},
            {"ホンル", "鸿璐"},
            {"良秀", "良秀"},
    };
    for (const SinnerNamePair &pair : kSinnerNames) {
        const char *const candidates[] = {pair.jp, pair.zh};
        for (const char *candidate : candidates) {
            if (candidate == nullptr || source.find(candidate) == std::string::npos) {
                continue;
            }
            std::string template_plain = replace_all_substrings(source, candidate, "{0}");
            if (lookup_template_translation(template_plain, pair.zh, translated)) {
                return true;
            }
            std::string bracketed = std::string("[") + candidate + "]";
            if (source.find(bracketed) != std::string::npos) {
                std::string template_bracket = replace_all_substrings(source, bracketed, "{0}");
                if (lookup_template_translation(template_bracket, pair.zh, translated)) {
                    return true;
                }
            }
        }
    }
    if (contains_japanese_kana(source)) {
        std::string replaced = source;
        for (const SinnerNamePair &pair : kSinnerNames) {
            if (strcmp(pair.jp, pair.zh) == 0) continue;
            if (replaced.find(pair.jp) != std::string::npos) {
                replaced = replace_all_substrings(replaced, pair.jp, pair.zh);
            }
        }
        if (replaced != source && !contains_japanese_kana(replaced)) {
            *translated = std::move(replaced);
            return true;
        }
    }
    return false;
}

bool translate_multiline_exact(const std::string &source, std::string *translation) {
    if (translation == nullptr || source.find('\n') == std::string::npos) {
        return false;
    }
    std::string result;
    result.reserve(source.size());
    bool translated_nonempty_line = false;
    size_t line_start = 0;
    while (line_start <= source.size()) {
        const size_t newline = source.find('\n', line_start);
        std::string line = source.substr(
                line_start, newline == std::string::npos ? std::string::npos : newline - line_start);
        const bool has_cr = !line.empty() && line.back() == '\r';
        if (has_cr) line.pop_back();
        // TMP formatters can retain a UTF-8 non-breaking space at a line boundary.
        // It is layout-only and must not prevent an otherwise exact line match.
        while (line.size() >= 2 &&
               static_cast<unsigned char>(line[line.size() - 2]) == 0xc2 &&
               static_cast<unsigned char>(line.back()) == 0xa0) {
            line.resize(line.size() - 2);
        }
        while (line.size() >= 2 &&
               static_cast<unsigned char>(line[0]) == 0xc2 &&
               static_cast<unsigned char>(line[1]) == 0xa0) {
            line.erase(0, 2);
        }
        if (!line.empty()) {
            auto exact = g_index.find(line);
            auto fallback = g_fallback_index.find(line);
            if (exact != g_index.end()) {
                result += exact->second;
                g_exact_matches.fetch_add(1, std::memory_order_relaxed);
                translated_nonempty_line = true;
            } else if (fallback != g_fallback_index.end()) {
                result += fallback->second;
                g_fallback_matches.fetch_add(1, std::memory_order_relaxed);
                translated_nonempty_line = true;
            } else {
                std::string line_sinner_translated;
                if (translate_sinner_formatted_text(line, &line_sinner_translated)) {
                    result += line_sinner_translated;
                    g_exact_matches.fetch_add(1, std::memory_order_relaxed);
                    translated_nonempty_line = true;
                } else if (!contains_japanese_kana(line)) {
                    // An earlier formatter may already have translated this line.
                    result += line;
                } else {
                    return false;
                }
            }
        }
        if (has_cr) result.push_back('\r');
        if (newline == std::string::npos) break;
        result.push_back('\n');
        line_start = newline + 1;
    }
    if (!translated_nonempty_line) return false;
    *translation = std::move(result);
    uint64_t hits = g_multiline_matches.fetch_add(1, std::memory_order_relaxed) + 1;
    if (hits == 1 || hits % 500 == 0) {
        LT_LOGI("Multiline exact translation hits=%llu",
                static_cast<unsigned long long>(hits));
    }
    return true;
}

bool restore_ellipsis_truncated_translation(const std::string &text,
                                            std::string *restored) {
    size_t prefix_len = 0;
    if (text.size() > 3 && text.compare(text.size() - 3, 3, "...") == 0) {
        prefix_len = text.size() - 3;
    } else if (text.size() > 3 &&
               text.compare(text.size() - 3, 3, "\xe2\x80\xa6") == 0) {
        prefix_len = text.size() - 3;
    }
    if (prefix_len < 6) return false;
    bool found = false;
    std::string best_match;
    pthread_mutex_lock(&g_font_state_lock);
    for (const std::string &entry : g_translated_managed_texts) {
        if (entry == text) continue;
        if (entry.size() > prefix_len &&
            entry.find('\n') == std::string::npos &&
            entry.compare(0, prefix_len, text, 0, prefix_len) == 0) {
            if (!found || entry.size() < best_match.size()) {
                best_match = entry;
                found = true;
            }
        }
    }
    pthread_mutex_unlock(&g_font_state_lock);
    if (found && restored != nullptr) *restored = std::move(best_match);
    return found;
}

bool is_registered_translation_text(const std::string &text) {
    pthread_mutex_lock(&g_font_state_lock);
    bool registered = g_translated_managed_texts.find(text) != g_translated_managed_texts.end();
    pthread_mutex_unlock(&g_font_state_lock);
    if (!registered) {
        std::string restored;
        if (restore_ellipsis_truncated_translation(text, &restored)) {
            pthread_mutex_lock(&g_font_state_lock);
            g_translated_managed_texts.insert(text);
            pthread_mutex_unlock(&g_font_state_lock);
            registered = true;
        }
    }
    return registered;
}

std::string translation_context_key(const std::string &prefix, const std::string &source) {
    std::string key(prefix);
    key.push_back('\x1f');
    key.append(source);
    return key;
}

void *translate_managed_string(
        void *managed_text, const std::vector<std::string> *formatter_templates = nullptr,
        bool formatter_provenance_incomplete = false,
        const std::string *translation_context = nullptr) {
    if (managed_text == nullptr || g_string_length == nullptr || g_string_chars == nullptr) {
        return managed_text;
    }
    int32_t length = g_string_length(managed_text);
    std::string source;
    if (length < 0 || length > 1024 * 1024 ||
        !utf16_to_utf8(g_string_chars(managed_text), length, &source)) {
        return managed_text;
    }
    const char *probe_kind = known_text_probe_kind(source);
    const size_t probe_template_count =
            formatter_templates == nullptr ? 0 : formatter_templates->size();
    const auto probe = [&](const char *stage, const std::string &result,
                           bool translated, bool incomplete) {
        log_known_text_probe(
                probe_kind, stage, source, result, translated, incomplete,
                probe_template_count);
    };
    // Formatter/object hooks can hand the same translated value to TMP after the
    // original managed object has been copied. Never feed a known Chinese output
    // back through the short-term trie: terms such as 最高 are also valid Japanese
    // source strings and would otherwise be translated a second time.
    if (is_registered_translation_text(source)) {
        remember_formatter_translation(source);
        probe("already-registered", source, true, false);
        return managed_text;
    }
    std::string repaired;
    if (repair_unresolved_localization_tags(source, &repaired) &&
        is_usable_display_text(repaired)) {
        void *replacement = new_managed_string(repaired);
        if (replacement == nullptr || !remember_translation_owned_string(replacement)) {
            probe("tag-repair-owned-failed", repaired, false, false);
            return managed_text;
        }
        remember_formatter_translation(repaired);
        pthread_mutex_lock(&g_font_state_lock);
        g_translated_managed_texts.insert(repaired);
        pthread_mutex_unlock(&g_font_state_lock);
        uint64_t hits = g_tag_repair_matches.fetch_add(1, std::memory_order_relaxed) + 1;
        if (hits == 1 || hits % 500 == 0) {
            LT_LOGI("Repaired unresolved localization tags=%llu",
                    static_cast<unsigned long long>(hits));
        }
        probe("tag-repaired", repaired, true, false);
        return replacement;
    }
    if (is_translation_owned_string(managed_text)) {
        remember_formatter_translation(source);
        probe("owned-managed-text", source, true, false);
        return managed_text;
    }
    auto protected_spans = formatter_templates == nullptr ?
            std::vector<limbus_translation_guard::ProtectedSpan>() :
            limbus_translation_guard::protected_template_spans(source, *formatter_templates);
    std::unordered_map<std::string, std::string>::const_iterator match = g_index.find(source);
    std::unordered_map<std::string, std::string>::const_iterator fallback_match =
            g_fallback_index.find(source);
    std::string translated;
    std::unordered_map<std::string, std::string>::const_iterator context_match =
            g_context_index.end();
    if (translation_context != nullptr) {
        context_match = g_context_index.find(
                translation_context_key(*translation_context, source));
    }
    const bool exact_ui_override =
            protected_spans.empty() && !formatter_provenance_incomplete &&
            translate_exact_ui_text(source, &translated);
    const char *probe_stage = nullptr;
    if (formatter_provenance_incomplete) {
        // 来源模板截断时不能猜测哪些中文片段可再译；保留 formatter 的现有输出并续传凭据。
        translated = source;
        probe_stage = "formatter-incomplete";
    } else if (context_match != g_context_index.end()) {
        translated = context_match->second;
        probe_stage = "context-match";
        g_exact_matches.fetch_add(1, std::memory_order_relaxed);
    } else if (exact_ui_override) {
        probe_stage = "exact-ui-match";
        g_exact_matches.fetch_add(1, std::memory_order_relaxed);
    } else if (protected_spans.empty() && match != g_index.end()) {
        translated = match->second;
        probe_stage = "exact-index-match";
        g_exact_matches.fetch_add(1, std::memory_order_relaxed);
    } else if (protected_spans.empty() && fallback_match != g_fallback_index.end()) {
        translated = fallback_match->second;
        probe_stage = "fallback-index-match";
        g_fallback_matches.fetch_add(1, std::memory_order_relaxed);
    } else {
        std::string normalized;
        bool line_height_normalized = protected_spans.empty() && strip_line_height_tags(source, &normalized);
        if (line_height_normalized) {
            match = g_index.find(normalized);
            fallback_match = g_fallback_index.find(normalized);
        }
        if (line_height_normalized && match != g_index.end()) {
            translated = apply_dante_line_height(match->second);
            probe_stage = "normalized-index-match";
            g_layout_matches.fetch_add(1, std::memory_order_relaxed);
        } else if (line_height_normalized && fallback_match != g_fallback_index.end()) {
            translated = apply_dante_line_height(fallback_match->second);
            probe_stage = "normalized-fallback-match";
            g_fallback_matches.fetch_add(1, std::memory_order_relaxed);
        } else if (protected_spans.empty() && translate_sinner_formatted_text(source, &translated)) {
            probe_stage = "sinner-template-match";
            g_exact_matches.fetch_add(1, std::memory_order_relaxed);
        } else if (protected_spans.empty() && translate_multiline_exact(source, &translated)) {
            // The formatter joined several already-localized fields with newlines.
            probe_stage = "multiline-match";
        } else if (!translate_embedded_terms(source, &translated, &protected_spans)) {
            if (!protected_spans.empty()) {
                // 原 formatter 已使用本次汉化字段，即使只替换了数字也要传递新的来源凭据。
                translated = source;
                probe_stage = "protected-passthrough";
            } else {
                // Metadata only: no game text, user input or account data in logs.
                uint64_t misses = g_unmatched_texts.fetch_add(1, std::memory_order_relaxed) + 1;
                if (misses == 1 || misses % 1000 == 0) {
                    LT_LOGI("Text lookup totals exact=%llu layout=%llu terms=%llu fallback=%llu unchanged=%llu",
                            static_cast<unsigned long long>(g_exact_matches.load(std::memory_order_relaxed)),
                            static_cast<unsigned long long>(g_layout_matches.load(std::memory_order_relaxed)),
                            static_cast<unsigned long long>(g_term_matches.load(std::memory_order_relaxed)),
                            static_cast<unsigned long long>(g_fallback_matches.load(std::memory_order_relaxed)),
                            static_cast<unsigned long long>(misses));
                }
                probe("miss", source, false, false);
                return managed_text;
            }
        } else {
            probe_stage = "embedded-term-match";
            g_term_matches.fetch_add(1, std::memory_order_relaxed);
        }
    }
    if (repair_translated_skill_tag_placeholders(&translated)) {
        g_tag_repair_matches.fetch_add(1, std::memory_order_relaxed);
    }
    if (!is_usable_display_text(translated)) {
        probe(probe_stage == nullptr ? "unusable-result" : probe_stage,
              translated, false, false);
        return managed_text;
    }
    void *replacement = new_managed_string(translated);
    if (replacement != nullptr) {
        if (!remember_translation_owned_string(replacement)) {
            probe("owned-limit", translated, false, formatter_provenance_incomplete);
            return managed_text;
        }
        remember_formatter_translation(translated);
        pthread_mutex_lock(&g_font_state_lock);
        // 只登记接管层确实生成过的完整译文，不能按“含非 ASCII”泛化为中文主字体。
        g_translated_managed_texts.insert(translated);
        pthread_mutex_unlock(&g_font_state_lock);
        uint64_t hits = g_acquisition_hits.fetch_add(1, std::memory_order_relaxed) + 1;
        if (hits == 1 || hits % 500 == 0) {
            LT_LOGI("Text acquisition translation hits=%llu",
                    static_cast<unsigned long long>(hits));
        }
    }
    probe(probe_stage == nullptr ? "replacement-passthrough" : probe_stage,
          translated, replacement != managed_text, formatter_provenance_incomplete);
    return replacement == nullptr ? managed_text : replacement;
}

/** 用 BattleHint 资源表和稳定数字 ID 接管游戏仍未更新的旧版小贴士文案。 */
void *translate_battle_hint_by_id(
        const std::string &table, void *managed_id, void *managed_text) {
    if (table.empty() || managed_id == nullptr || managed_text == nullptr ||
        g_battle_hint_index.empty()) {
        return nullptr;
    }
    // acquisition1 is hooked directly on the IL2CPP instance method, so the
    // second ABI slot carries the integer value itself rather than an address.
    int32_t id = static_cast<int32_t>(reinterpret_cast<intptr_t>(managed_id));
    const std::string key = table + '\x1f' + std::to_string(id);
    const bool probe_id = id == 7;
    auto match = g_battle_hint_index.find(key);
    if (match == g_battle_hint_index.end() ||
        !is_usable_display_text(match->second)) {
        if (probe_id) {
            LT_LOGI("BattleHint ID probe table=%s id=%d lookup=miss",
                    table.c_str(), id);
        }
        return nullptr;
    }
    void *replacement = new_managed_string(match->second);
    if (replacement == nullptr || !remember_translation_owned_string(replacement)) {
        return nullptr;
    }
    remember_formatter_translation(match->second);
    pthread_mutex_lock(&g_font_state_lock);
    g_translated_managed_texts.insert(match->second);
    pthread_mutex_unlock(&g_font_state_lock);
    if (probe_id) {
        LT_LOGI("BattleHint ID probe table=%s id=%d lookup=hit resultChars=%zu result=%s",
                table.c_str(), id, match->second.size(), match->second.c_str());
    }
    uint64_t hits = g_battle_hint_id_hits.fetch_add(1, std::memory_order_relaxed) + 1;
    if (hits == 1 || hits % 100 == 0) {
        LT_LOGI("BattleHint ID translation hits=%llu table=%s id=%d",
                static_cast<unsigned long long>(hits), table.c_str(), id);
    }
    return replacement;
}

bool is_translated_managed_string(void *managed_text) {
    if (managed_text == nullptr || g_string_length == nullptr || g_string_chars == nullptr) {
        return false;
    }
    int32_t length = g_string_length(managed_text);
    std::string text;
    if (length < 0 || length > 1024 * 1024 ||
        !utf16_to_utf8(g_string_chars(managed_text), length, &text)) {
        return false;
    }
    pthread_mutex_lock(&g_font_state_lock);
    bool translated = g_translated_managed_texts.find(text) !=
            g_translated_managed_texts.end();
    pthread_mutex_unlock(&g_font_state_lock);
    return translated;
}

bool translate_exact_ui_text(const std::string &source, std::string *translation) {
    if (translation == nullptr) return false;
    // 设置菜单的部分固定控件使用独立的日文 UI 表，没有进入通用文本索引。
    // 这里只做逐项精确匹配，避免短词替换误伤剧情、编号或用户输入。
    static const std::unordered_map<std::string, std::string> overrides = {
            {"戻る", "返回"},
            {"環境設定", "设置"},
            {"環境設置", "设置"},
            {"基本設定", "基础设置"},
            {"グラフィック", "图像"},
            {"音声", "音量"},
            {"通知", "通知"},
            {"その他", "杂项"},
            {"ダウンロード", "下载"},
            {"ダウンロード完了", "下载完成"},
            {"映像リソース", "视频资源"},
            {"音声リソース", "音频资源"},
            {"アニメーションリソース", "动效资源"},
            {"E.G.O演出", "E.G.O动效"},
            {"一日に一回だけ再生", "每天一次"},
            {"低", "低"},
            {"中", "中"},
            {"高", "高"},
            {"解像度", "分辨率"},
            {"ウィンドウ", "窗口化"},
            {"全画面", "全屏"},
            {"テクスチャの品質", "材质质量"},
            {"画質が高くなるほど、電力消費の増加や発熱を引き起こす可能性があります。",
             "画质越高，越可能导致功耗增加和设备发热。"},
            {"フレームレートが高くなるほど、電力消費の増加や発熱を引き起こす可能性があります。",
             "帧率越高，越可能导致功耗增加和设备发热。"},
            {"ポストプロセス", "后处理"},
            {"戦闘にて使うカメラ効果を制御します。パフォーマンスに影響を及ぼします。",
             "控制战斗中的镜头效果。影响游戏性能。"},
            {"アンチエイリアス", "抗锯齿"},
            {"設定の初期化", "恢复默认"},
            {"全ての設定を初期化しますか？", "要将所有选项都恢复为默认状态吗？"},
            {"レンダリングの加速", "加速渲染"},
            {"マスター", "总音量"},
            {"効果音", "音效音量"},
            {"ボイス", "语音音量"},
            {"アナウンサー", "播报员"},
            {"エンケファリン充填完了の通知", "恢复脑啡肽"},
            {"プロモーション通知", "广告通知"},
            {"プロモーション通知(夜間)", "广告通知（夜间）"},
            {"言語変更", "语言设置"},
            {"言語更换", "语言设置"},
            {"言語変更はゲーム再起動時に適用されます。",
             "更改语言将在游戏重启后生效。"},
            {"メインストーリー", "主线剧情"},
            {"サポートサービス", "联系客服"},
            {"Google連携", "绑定Google账号"},
            {"Apple連携", "绑定Apple账号"},
            {"アカウント", "账号"},
            {"アカウント削除", "停用账号"},
            {"連携済みアカウント", "关联账号"},
            {"ご利用のアカウントから退会時、全てのアカウント情報および購入情報が削除されます。",
             "账号停用后，所有游戏数据和消费记录都会被清除。"},
            {"アカウント削除期間は7日で、期間内にログインすると削除がキャンセルできます。",
             "您将获得 7 天缓冲期，此期间内登录即可取消停用。"},
            {"※上記の内容に同意するなら、\nテキストボックス内の文字を入力してください。",
             "※ 若您同意上述条款，\n请输入框内字符。"},
            {"該当アカウントは削除処理中です。\n残り時間：{0}日{1}時間{2}分\n"
             "今ログインすれば復旧可能です。\nアカウントを復旧しますか？",
             "该账号目前正处于停用倒计时。\n剩余时间：{0}天{1}小时{2}分\n"
             "现在登录即可恢复该账号。\n要恢复账号吗？"},
            {"復旧", "恢复"},
            {"削除申請", "停用账号"},
            {"削除申請に成功しました。", "已成功请求停用。"},
            {"ブルーム", "泛光"},
            {"ブラー", "模糊"},
            {"光のにじむ効果を制御します。パフォーマンスに影響します。",
             "控制泛光模糊效果。影响游戏性能。"},
            {"ぼかし効果を制御します。パフォーマンスに影響します。",
             "控制模糊效果。影响游戏性能。"},
            {"一般FPS", "普通FPS"},
            {"戦闘FPS", "战斗FPS"},
            {"レンダリングスケール", "渲染比例"},
            {"PC環境では「高」、モバイル環境では「中」以下の値を推奨します。\n"
             "レンダリングスケールの値が高くなるほど、電力消耗増加および発熱現象が誘発されることがあります。",
             "PC端推荐设置为[高]，手机端推荐设置为[中]或[低]。\n"
             "更高的渲染比例可能会导致设备发热和功耗上升。"},
            {"ストーリーボイス", "剧情语音"},
            {"ストーリーボイスのダウンロード・削除を管理します。",
             "下载或删除剧情语音。"},
            {"管理", "管理"},
            {"自動投擲コイン", "自动投掷硬币"},
            {"戦闘中表示詳細情報UI", "战斗中显示详细信息UI"},
            {"戦闘中の詳細情報UI表示", "战斗中显示详细信息UI"},
            {"サービス利用約款", "用户协议"},
            {"利用約款", "用户协议"},
            {"個人情報収集および利用に関する案内", "信息与隐私政策"},
            {"同意します。", "我同意。"},
            {"連携コード生成", "生成继承码"},
            {"連携コード入力", "输入继承码"},
            {"連携解除", "解除绑定"},
            {"オン", "开启"},
            {"オフ", "关闭"},
    };
    auto match = overrides.find(source);
    if (match == overrides.end()) return false;
    *translation = match->second;
    return true;
}

bool is_display_string_field(const char *name) {
    if (name == nullptr) return false;
    static const char *const fields[] = {
            "content", "dialog", "dlg", "teller", "name", "nameWithTitle",
            "desc", "description", "title", "summary", "flavor", "place",
            "shortName", "abName", "simpleDesc", "successDesc", "failureDesc",
            "message", "messageDesc", "result", "text", "subText", "mainText",
            "rawDesc", "acquisitionMethod", "skinItemTitle", "skinItemDesc",
            "oneLineTitle", "nickName", "longName", "specialName",
            "abnormalityName", "panicName", "behaveDesc", "eventDesc", "prevDesc",
            "subDesc", "panicDescription", "lowMoraleDescription", "codeName", "clue",
            "sentence", "story", "openCondition", "openConditionNumber",
            "relatedChapterText", "askLevelUp", "company", "area", "chapter",
            "chapterNumber", "chaptertitle", "parttitle", "timeline", "teacher",
            "add", "min", "variation", "variation2", "speaker", "displayName", "statText",
            "goalDescription1", "goalDescription2", "goalDescription3", "goalDescription4",
            "goalDescription5", "goalDescription6", "goalDescription7", "goalDescription8"
    };
    for (const char *field : fields) {
        if (strcmp(name, field) == 0) return true;
    }
    return false;
}

std::string read_object_stable_identity(void *instance) {
    if (instance == nullptr || g_object_get_class == nullptr ||
        g_class_get_parent == nullptr || g_class_get_fields == nullptr ||
        g_field_get_name == nullptr || g_field_get_type == nullptr ||
        g_field_get_value == nullptr || g_type_get_name == nullptr ||
        g_il2cpp_free == nullptr) {
        return {};
    }
    static const char *const identity_fields[] = {
            "id", "ID", "key", "originalId", "index", "idx", "level"
    };
    for (void *klass = g_object_get_class(instance); klass != nullptr;
         klass = g_class_get_parent(klass)) {
        void *iterator = nullptr;
        const void *field = nullptr;
        while ((field = g_class_get_fields(klass, &iterator)) != nullptr) {
            const char *field_name = g_field_get_name(field);
            if (field_name == nullptr) continue;
            bool known_identity = std::any_of(
                    std::begin(identity_fields), std::end(identity_fields),
                    [&](const char *candidate) { return strcmp(field_name, candidate) == 0; });
            if (!known_identity) continue;
            char *type_name = g_type_get_name(g_field_get_type(field));
            std::string value;
            if (type_name != nullptr && strcmp(type_name, "System.String") == 0) {
                void *object = nullptr;
                g_field_get_value(instance, field, &object);
                if (object != nullptr && g_string_length != nullptr && g_string_chars != nullptr) {
                    int32_t length = g_string_length(object);
                    if (length >= 0 && length <= 160) {
                        utf16_to_utf8(g_string_chars(object), length, &value);
                    }
                }
            } else if (type_name != nullptr && strcmp(type_name, "System.Int32") == 0) {
                int32_t number = 0;
                g_field_get_value(instance, field, &number);
                value = std::to_string(number);
            } else if (type_name != nullptr && strcmp(type_name, "System.Int64") == 0) {
                int64_t number = 0;
                g_field_get_value(instance, field, &number);
                value = std::to_string(number);
            }
            if (type_name != nullptr) g_il2cpp_free(type_name);
            if (!value.empty() && value.size() <= 160) {
                return std::string(field_name) + "=" + value;
            }
        }
    }
    return {};
}

void translate_string_collection_elements(void *collection, const std::string &context_prefix) {
    if (collection == nullptr || g_object_get_class == nullptr ||
        g_class_get_parent == nullptr || g_class_get_method == nullptr ||
        g_runtime_invoke == nullptr || g_object_unbox == nullptr) {
        return;
    }
    void *klass = g_object_get_class(collection);
    const void *count_method = nullptr;
    const void *get_item_method = nullptr;
    const void *set_item_method = nullptr;
    const void *length_method = nullptr;
    for (void *cur = klass; cur != nullptr; cur = g_class_get_parent(cur)) {
        if (count_method == nullptr) count_method = g_class_get_method(cur, "get_Count", 0);
        if (get_item_method == nullptr) get_item_method = g_class_get_method(cur, "get_Item", 1);
        if (set_item_method == nullptr) set_item_method = g_class_get_method(cur, "set_Item", 2);
        if (length_method == nullptr) length_method = g_class_get_method(cur, "get_Length", 0);
    }
    const void *active_count_method = count_method != nullptr ? count_method : length_method;
    if (active_count_method == nullptr) return;
    void *exception = nullptr;
    void *boxed_count = g_runtime_invoke(active_count_method, collection, nullptr, &exception);
    int32_t *count_ptr = (exception == nullptr && boxed_count != nullptr) ?
            static_cast<int32_t *>(g_object_unbox(boxed_count)) : nullptr;
    if (count_ptr == nullptr) return;
    int32_t count = *count_ptr;
    if (count <= 0 || count > 256) return;
    const bool array_like = set_item_method == nullptr && length_method != nullptr;
    if (set_item_method == nullptr && !array_like) return;
    for (int32_t index = 0; index < count; ++index) {
        void *item = nullptr;
        if (get_item_method != nullptr) {
            void *args[] = {&index};
            exception = nullptr;
            item = g_runtime_invoke(get_item_method, collection, args, &exception);
            if (exception != nullptr) item = nullptr;
        } else if (array_like) {
            void *address = g_array_addr_with_size == nullptr ? nullptr :
                    g_array_addr_with_size(
                            collection, static_cast<int32_t>(sizeof(void *)),
                            static_cast<uint32_t>(index));
            if (address == nullptr) {
                constexpr size_t kIl2CppArrayVectorOffset = sizeof(void *) * 4;
                address = reinterpret_cast<unsigned char *>(collection) +
                        kIl2CppArrayVectorOffset + static_cast<size_t>(index) * sizeof(void *);
            }
            if (address != nullptr) memcpy(&item, address, sizeof(item));
        }
        if (item == nullptr) continue;
        void *replacement = translate_managed_string(
                item, nullptr, false,
                context_prefix.empty() ? nullptr : &context_prefix);
        if (replacement == nullptr || replacement == item) continue;
        if (set_item_method != nullptr) {
            void *args[] = {&index, replacement};
            exception = nullptr;
            g_runtime_invoke(set_item_method, collection, args, &exception);
        } else if (array_like) {
            void *address = g_array_addr_with_size == nullptr ? nullptr :
                    g_array_addr_with_size(
                            collection, static_cast<int32_t>(sizeof(void *)),
                            static_cast<uint32_t>(index));
            if (address == nullptr) {
                constexpr size_t kIl2CppArrayVectorOffset = sizeof(void *) * 4;
                address = reinterpret_cast<unsigned char *>(collection) +
                        kIl2CppArrayVectorOffset + static_cast<size_t>(index) * sizeof(void *);
            }
            if (address != nullptr) memcpy(address, &replacement, sizeof(replacement));
        }
    }
}

void translate_object_string_fields(void *instance) {
    if (instance == nullptr || g_object_get_class == nullptr || g_class_get_parent == nullptr ||
        g_class_get_fields == nullptr || g_field_get_name == nullptr ||
        g_field_get_type == nullptr || g_field_get_value == nullptr ||
        g_field_set_value == nullptr || g_type_get_name == nullptr || g_il2cpp_free == nullptr) {
        return;
    }
    const std::string identity = read_object_stable_identity(instance);
    for (void *klass = g_object_get_class(instance); klass != nullptr;
         klass = g_class_get_parent(klass)) {
        void *iterator = nullptr;
        const void *field = nullptr;
        while ((field = g_class_get_fields(klass, &iterator)) != nullptr) {
            const char *field_name = g_field_get_name(field);
            if (!is_display_string_field(field_name)) continue;
            char *type_name = g_type_get_name(g_field_get_type(field));
            bool is_string = type_name != nullptr && strcmp(type_name, "System.String") == 0;
            bool is_string_collection = type_name != nullptr && !is_string &&
                    strstr(type_name, "System.String") != nullptr;
            if (type_name != nullptr) g_il2cpp_free(type_name);
            if (!is_string && !is_string_collection) continue;
            void *value = nullptr;
            g_field_get_value(instance, field, &value);
            std::string context_prefix;
            if (!identity.empty() && field_name != nullptr) {
                context_prefix = identity;
                context_prefix.push_back('\x1f');
                context_prefix.append(field_name);
            }
            if (is_string) {
                void *replacement = translate_managed_string(
                        value, nullptr, false,
                        context_prefix.empty() ? nullptr : &context_prefix);
                if (replacement != value) g_field_set_value(instance, field, replacement);
            } else {
                translate_string_collection_elements(value, context_prefix);
            }
        }
    }
}

bool is_nested_text_field(const char *name) {
    if (name == nullptr) return false;
    static const char *const fields[] = {
            "levelList", "levellist", "coinList", "coinlist",
            "coinDescs", "coindescs", "options", "storyList", "storylist",
            "simpleDesc", "simpledesc", "stageList", "stagelist",
            "nameList", "namelist", "effects", "texts", "textsEffects", "textseffects"
    };
    for (const char *field : fields) {
        if (strcmp(name, field) == 0) return true;
    }
    return false;
}

void translate_object_graph_impl(void *instance, int depth,
                                 std::unordered_set<void *> *visited) {
    if (instance == nullptr || depth > 4 || visited == nullptr ||
        !visited->insert(instance).second) {
        return;
    }
    translate_object_string_fields(instance);
    if (g_object_get_class == nullptr || g_class_get_parent == nullptr ||
        g_class_get_fields == nullptr || g_field_get_name == nullptr ||
        g_field_get_value == nullptr || g_class_get_method == nullptr ||
        g_runtime_invoke == nullptr || g_object_unbox == nullptr) {
        return;
    }
    for (void *klass = g_object_get_class(instance); klass != nullptr;
         klass = g_class_get_parent(klass)) {
        void *iterator = nullptr;
        const void *field = nullptr;
        while ((field = g_class_get_fields(klass, &iterator)) != nullptr) {
            const char *field_name = g_field_get_name(field);
            if (!is_nested_text_field(field_name)) continue;
            void *list = nullptr;
            g_field_get_value(instance, field, &list);
            void *list_class = list == nullptr ? nullptr : g_object_get_class(list);
            const void *count_method = list_class == nullptr ? nullptr :
                    g_class_get_method(list_class, "get_Count", 0);
            const void *item_method = list_class == nullptr ? nullptr :
                    g_class_get_method(list_class, "get_Item", 1);
            if (count_method == nullptr || item_method == nullptr) continue;
            void *exception = nullptr;
            void *boxed_count = g_runtime_invoke(count_method, list, nullptr, &exception);
            int32_t *count_value = exception == nullptr && boxed_count != nullptr ?
                    static_cast<int32_t *>(g_object_unbox(boxed_count)) : nullptr;
            int32_t count = count_value == nullptr ? 0 : *count_value;
            if (count < 0 || count > 512) continue;
            uint64_t nested_hits = g_nested_list_hits.fetch_add(1, std::memory_order_relaxed) + 1;
            if (nested_hits == 1 || nested_hits % 1000 == 0) {
                LT_LOGI("Nested text list traversal hits=%llu field=%s count=%d depth=%d",
                        static_cast<unsigned long long>(nested_hits), field_name, count, depth);
            }
            for (int32_t index = 0; index < count; ++index) {
                void *args[] = {&index};
                exception = nullptr;
                void *child = g_runtime_invoke(item_method, list, args, &exception);
                if (exception == nullptr && child != nullptr) {
                    translate_object_graph_impl(child, depth + 1, visited);
                }
            }
        }
    }
}

void translate_object_graph(void *instance) {
    std::unordered_set<void *> visited;
    translate_object_graph_impl(instance, 0, &visited);
}

typedef void *(*acquisition0_fn)(void *, const void *);
typedef void *(*acquisition1_fn)(void *, void *, const void *);
typedef void *(*acquisition2_fn)(void *, void *, void *, const void *);
const size_t kAcquisitionSlots = 64;
acquisition0_fn g_acquisition0[kAcquisitionSlots] = {};
acquisition1_fn g_acquisition1[kAcquisitionSlots] = {};
acquisition2_fn g_acquisition2[kAcquisitionSlots] = {};
std::string g_acquisition1_battle_hint_tables[kAcquisitionSlots];
std::string g_acquisition2_battle_hint_tables[kAcquisitionSlots];

bool battle_hint_table_name(
        const std::string &class_name, std::string *table_name) {
    static const std::string prefix("TextData_");
    if (table_name == nullptr ||
        class_name.compare(0, prefix.size(), prefix) != 0) {
        return false;
    }
    std::string candidate = class_name.substr(prefix.size());
    if (candidate != "BattleHint" &&
        candidate.compare(0, strlen("BattleHint_"), "BattleHint_") != 0) {
        return false;
    }
    *table_name = std::move(candidate);
    return true;
}

void *invoke_acquisition0(size_t slot, void *instance, const void *method) {
    FormatterTranslationScope scope;
    translate_object_graph(instance);
    void *formatted = g_acquisition0[slot](instance, method);
    void *result = translate_managed_string(formatted, &scope.templates, scope.truncated);
    remember_formatter_result(&scope, result);
    return result;
}
void *invoke_acquisition1(size_t slot, void *instance, void *arg0, const void *method) {
    FormatterTranslationScope scope;
    translate_object_graph(instance);
    void *formatted = g_acquisition1[slot](instance, arg0, method);
    void *result = translate_managed_string(formatted, &scope.templates, scope.truncated);
    if (result == formatted && !g_acquisition1_battle_hint_tables[slot].empty()) {
        void *battle_hint = translate_battle_hint_by_id(
                g_acquisition1_battle_hint_tables[slot], arg0, formatted);
        if (battle_hint != nullptr) result = battle_hint;
    }
    remember_formatter_result(&scope, result);
    return result;
}
void *invoke_acquisition2(size_t slot, void *instance, void *arg0, void *arg1,
                          const void *method) {
    FormatterTranslationScope scope;
    translate_object_graph(instance);
    void *formatted = g_acquisition2[slot](instance, arg0, arg1, method);
    void *result = translate_managed_string(formatted, &scope.templates, scope.truncated);
    if (result == formatted && !g_acquisition2_battle_hint_tables[slot].empty()) {
        void *battle_hint = translate_battle_hint_by_id(
                g_acquisition2_battle_hint_tables[slot], arg0, formatted);
        if (battle_hint != nullptr) result = battle_hint;
    }
    remember_formatter_result(&scope, result);
    return result;
}

#define ACQUISITION_SLOTS(X) \
    X(0) X(1) X(2) X(3) X(4) X(5) X(6) X(7) \
    X(8) X(9) X(10) X(11) X(12) X(13) X(14) X(15) \
    X(16) X(17) X(18) X(19) X(20) X(21) X(22) X(23) \
    X(24) X(25) X(26) X(27) X(28) X(29) X(30) X(31) \
    X(32) X(33) X(34) X(35) X(36) X(37) X(38) X(39) \
    X(40) X(41) X(42) X(43) X(44) X(45) X(46) X(47) \
    X(48) X(49) X(50) X(51) X(52) X(53) X(54) X(55) \
    X(56) X(57) X(58) X(59) X(60) X(61) X(62) X(63)
#define DEFINE_ACQUISITION_WRAPPERS(N) \
    void *replacement_acquisition0_##N(void *i, const void *m) { \
        return invoke_acquisition0(N, i, m); \
    } \
    void *replacement_acquisition1_##N(void *i, void *a, const void *m) { \
        return invoke_acquisition1(N, i, a, m); \
    } \
    void *replacement_acquisition2_##N(void *i, void *a, void *b, const void *m) { \
        return invoke_acquisition2(N, i, a, b, m); \
    }
ACQUISITION_SLOTS(DEFINE_ACQUISITION_WRAPPERS)
#undef DEFINE_ACQUISITION_WRAPPERS
#define ACQUISITION0_ENTRY(N) replacement_acquisition0_##N,
#define ACQUISITION1_ENTRY(N) replacement_acquisition1_##N,
#define ACQUISITION2_ENTRY(N) replacement_acquisition2_##N,
acquisition0_fn const kAcquisition0Replacements[] = {ACQUISITION_SLOTS(ACQUISITION0_ENTRY)};
acquisition1_fn const kAcquisition1Replacements[] = {ACQUISITION_SLOTS(ACQUISITION1_ENTRY)};
acquisition2_fn const kAcquisition2Replacements[] = {ACQUISITION_SLOTS(ACQUISITION2_ENTRY)};
#undef ACQUISITION0_ENTRY
#undef ACQUISITION1_ENTRY
#undef ACQUISITION2_ENTRY
#undef ACQUISITION_SLOTS

bool apply_chinese_material_underlay(void *material, bool force);
void apply_chinese_font_materials();
void apply_chinese_font_materials_for(void *asset);
bool find_tmp_component_state(void *instance, TmpComponentState *state);
void *ensure_translated_tmp_material(void *instance);
void *invoke_object_getter(const void *method_info, void *instance);
void clear_tmp_font_fallback_table(void *asset);
void prepend_tmp_font_fallback(void *asset, void *chinese_font);
bool is_mirror_dungeon_gift_or_shop_name(const std::string &component_name,
                                         const std::string &parent_name);
bool is_battle_winrate_control(
        const std::string &component_name, const std::string &parent_name,
        const std::string &display_text = std::string());
bool is_top_battle_state_control(
        const std::string &component_name, const std::string &parent_name);
bool is_bottom_battle_status_control(
        const std::string &component_name, const std::string &parent_name);
bool is_battle_skill_banner_control(
        const std::string &component_name, const std::string &parent_name);
void refresh_tmp_visual_colors(void *instance);
void restore_tmp_visual_colors(
        void *instance, const TmpComponentState &state);
bool refresh_tmp_source_material_style(
        void *instance, TmpComponentState *state);
void *preferred_chinese_font(const TmpComponentState &state, const std::string &component_name,
                             const std::string &parent_name);

bool pin_managed_font(void *asset, uintptr_t *handle, const char *label) {
    if (asset == nullptr || handle == nullptr || g_gchandle_new == nullptr ||
        g_gchandle_free == nullptr) {
        return false;
    }
    uintptr_t pinned = g_gchandle_new(asset, true);
    if (pinned == 0) {
        LT_LOGW("Unable to pin %s asset=%p; translation font disabled", label, asset);
        return false;
    }
    if (*handle != 0) g_gchandle_free(*handle);
    *handle = pinned;
    LT_LOGI("Pinned %s asset=%p handle=%llu", label, asset,
            static_cast<unsigned long long>(pinned));
    return true;
}

bool ui_font_has_character(void *font, uint16_t codepoint) {
    if (font == nullptr || g_ui_font_has_character_info == nullptr ||
        g_runtime_invoke == nullptr || g_object_unbox == nullptr) {
        return false;
    }
    uint16_t argument = codepoint;
    void *args[] = {&argument};
    void *exception = nullptr;
    void *boxed = g_runtime_invoke(
            g_ui_font_has_character_info, font, args, &exception);
    if (exception != nullptr || boxed == nullptr) return false;
    const bool *value = static_cast<const bool *>(g_object_unbox(boxed));
    return value != nullptr && *value;
}

std::string ui_font_missing_probe_glyphs(void *font) {
    static const struct {
        uint16_t codepoint;
        const char *label;
    } probes[] = {
            {0x63a2, "探"}, {0x7d22, "索"}, {0x7ed3, "结"}, {0x675f, "束"},
            {0x5956, "奖"}, {0x52b1, "励"}, {0x6bcf, "每"}, {0x5468, "周"},
            {0x52a0, "加"}, {0x6210, "成"}, {0x653e, "放"}, {0x5f03, "弃"},
    };
    if (font == nullptr || g_ui_font_has_character_info == nullptr) {
        return "unavailable";
    }
    std::string missing;
    for (const auto &probe : probes) {
        if (ui_font_has_character(font, probe.codepoint)) continue;
        if (!missing.empty()) missing += ',';
        missing += probe.label;
    }
    return missing.empty() ? "none" : missing;
}

bool ensure_tmp_font() {
    pthread_mutex_lock(&g_lock);
    if (!g_font_attempted) {
        g_font_attempted = true;
        std::string font_base(g_active_pointer);
        size_t separator = font_base.find_last_of('/');
        if (separator != std::string::npos) font_base.resize(separator);
        separator = font_base.find_last_of('/');
        if (separator != std::string::npos) font_base.resize(separator);
        std::string custom_font = font_base + "/runtime-font/CustomChineseFont.ttf";
        std::string bundled_font = font_base + "/runtime-font/ChineseFont-6541a94a.ttf";
        struct stat bundled_stat = {};
        struct stat custom_stat = {};
        // 描边与字面膨胀已经清零，优先使用汉化包自带的 Sarasa Bold 以贴近日文原版的
        // 中等偏粗字重；仅当汉化包未提供字体时才回退到内置 Regular。
        std::string font_file =
                (stat(custom_font.c_str(), &custom_stat) == 0 && custom_stat.st_size > 102400)
                ? custom_font
                : bundled_font;
        LT_LOGI("Preparing TMP Chinese font asset from path=%s", font_file.c_str());
        void *font_path = new_managed_string(font_file);
        int32_t face_index = 0;
        int32_t sampling_size = 32;
        int32_t atlas_padding = 9;
        int32_t render_mode = 4169;  // GlyphRenderMode.SDFAA.
        // The bundled Sarasa CJK face has substantially larger glyph contours
        // than the system fallback.  A 1024 atlas fills during the first skill
        // screen and TMP then drops glyphs that were already visible.
        int32_t atlas_width = 4096;
        int32_t atlas_height = 4096;
        int32_t population_mode = 1;  // AtlasPopulationMode.Dynamic.
        uint8_t multi_atlas = 1;
        void *asset_args[] = {
                font_path, &face_index, &sampling_size, &atlas_padding, &render_mode,
                &atlas_width, &atlas_height, &population_mode, &multi_atlas
        };
        void *exception = nullptr;
        if (font_path != nullptr && g_runtime_invoke != nullptr) {
            g_tmp_font_asset = g_runtime_invoke(
                    g_create_tmp_font_info, nullptr, asset_args, &exception);
        }
        if (g_tmp_font_asset == nullptr && font_file != bundled_font) {
            LT_LOGW("Custom TMP Chinese font failed to load; falling back to bundled font=%s", bundled_font.c_str());
            void *fallback_path = new_managed_string(bundled_font);
            asset_args[0] = fallback_path;
            exception = nullptr;
            if (fallback_path != nullptr && g_runtime_invoke != nullptr) {
                g_tmp_font_asset = g_runtime_invoke(
                        g_create_tmp_font_info, nullptr, asset_args, &exception);
            }
        }
        if (g_tmp_font_asset != nullptr &&
            !pin_managed_font(g_tmp_font_asset, &g_tmp_font_handle, "TMP Chinese font")) {
            // The raw runtime-invoke return value is not GC-visible by itself.
            // Never publish an unrooted wrapper to arbitrary TMP components.
            g_tmp_font_asset = nullptr;
        }
        if (g_tmp_font_asset != nullptr) {
            clear_tmp_font_fallback_table(g_tmp_font_asset);
            // 战斗技能与横幅在手机端会被缩到约 20～30px。正文继续使用 Bold，
            // 这类短标题额外加载 Regular，避免粗字重叠加后字腔发黑。
            if (stat(bundled_font.c_str(), &bundled_stat) == 0 &&
                bundled_stat.st_size > 102400) {
                void *regular_path = new_managed_string(bundled_font);
                void *regular_exception = nullptr;
                int32_t regular_atlas_width = 2048;
                int32_t regular_atlas_height = 2048;
                void *regular_args[] = {
                        regular_path, &face_index, &sampling_size, &atlas_padding, &render_mode,
                        &regular_atlas_width, &regular_atlas_height,
                        &population_mode, &multi_atlas
                };
                if (regular_path != nullptr && g_runtime_invoke != nullptr) {
                    g_tmp_regular_font_asset = g_runtime_invoke(
                            g_create_tmp_font_info, nullptr, regular_args, &regular_exception);
                }
                if (g_tmp_regular_font_asset != nullptr &&
                    !pin_managed_font(g_tmp_regular_font_asset, &g_tmp_regular_font_handle,
                                      "TMP Chinese Regular font")) {
                    g_tmp_regular_font_asset = nullptr;
                }
                if (g_tmp_regular_font_asset != nullptr) {
                    clear_tmp_font_fallback_table(g_tmp_regular_font_asset);
                }
                LT_LOGI("Dynamic CJK Regular font path=%s tmpFont=%p exception=%p",
                        bundled_font.c_str(), g_tmp_regular_font_asset, regular_exception);
            }
        }
        // 少数旧界面仍使用 UnityEngine.UI.Text，TMP_FontAsset 无法直接赋给它们。
        // 使用 Android 的 sans-serif-medium 复合字体族，并按字符回退到设备中文字体，
        // 让旧式导航和按钮文字不再比 TMP 正文明显偏细。
        void *ui_exception = nullptr;
        void *ui_font_name = new_managed_string("sans-serif-medium");
        int32_t ui_font_size = 32;
        void *ui_font_args[] = {ui_font_name, &ui_font_size};
        if (ui_font_name != nullptr && g_create_dynamic_font_info != nullptr &&
            g_runtime_invoke != nullptr) {
            g_ui_font = g_runtime_invoke(
                    g_create_dynamic_font_info, nullptr, ui_font_args, &ui_exception);
        }
        if (g_ui_font != nullptr &&
            !pin_managed_font(g_ui_font, &g_ui_font_handle, "UI Chinese font")) {
            g_ui_font = nullptr;
        }
        void *fallback_list = nullptr;
        void *add_exception = nullptr;
        if (g_tmp_font_asset != nullptr && exception == nullptr &&
            g_get_fallback_fonts_info != nullptr && g_class_get_method != nullptr) {
            fallback_list = g_runtime_invoke(
                    g_get_fallback_fonts_info, nullptr, nullptr, &add_exception);
            void *list_class = fallback_list == nullptr ? nullptr :
                    g_object_get_class(fallback_list);
            const void *add_method = list_class == nullptr ? nullptr :
                    g_class_get_method(list_class, "Add", 1);
            // Keep the game's original fallback table. Replacing it with the
            // Chinese fonts changes battle labels that intentionally rely on the
            // Japanese fallback chain, making their SDF materials unreadable.
            // Append the Chinese faces so translated glyphs can still fall back.
            void *add_args[] = {g_tmp_font_asset};
            if (add_method != nullptr) {
                g_runtime_invoke(add_method, fallback_list, add_args, &add_exception);
            }
            if (g_tmp_regular_font_asset != nullptr && add_method != nullptr) {
                void *regular_args[] = {g_tmp_regular_font_asset};
                g_runtime_invoke(add_method, fallback_list, regular_args, &add_exception);
            }
            LT_LOGI("Registered TMP fallback font list=%p add=%p exception=%p",
                    fallback_list, add_method, add_exception);
        }
        LT_LOGI("Dynamic CJK TMP font creation path=%s face=%d tmpFont=%p exception=%p",
                font_file.c_str(), face_index,
                g_tmp_font_asset, exception);
        LT_LOGI("Dynamic CJK UI font creation family=sans-serif-medium size=%d"
                " uiFont=%p exception=%p hasCharacter=%p missingProbeGlyphs=%s",
                ui_font_size, g_ui_font, ui_exception,
                g_ui_font_has_character_info,
                ui_font_missing_probe_glyphs(g_ui_font).c_str());
    }
    bool ready = g_tmp_font_asset != nullptr;
    pthread_mutex_unlock(&g_lock);
    if (ready) {
        apply_chinese_font_materials();
    }
    return ready;
}

bool invoke_float_getter(const void *method_info, void *instance, float *value) {
    if (method_info == nullptr || instance == nullptr || value == nullptr ||
        g_runtime_invoke == nullptr || g_object_unbox == nullptr) {
        return false;
    }
    void *exception = nullptr;
    void *boxed = g_runtime_invoke(method_info, instance, nullptr, &exception);
    void *unboxed = exception == nullptr && boxed != nullptr ? g_object_unbox(boxed) : nullptr;
    if (unboxed == nullptr) return false;
    memcpy(value, unboxed, sizeof(*value));
    return true;
}

void invoke_float_setter(const void *method_info, void *instance, float value) {
    if (method_info == nullptr || instance == nullptr || g_runtime_invoke == nullptr) return;
    void *args[] = {&value};
    void *exception = nullptr;
    g_runtime_invoke(method_info, instance, args, &exception);
}

bool invoke_color_getter(const void *method_info, void *instance, float color[4]) {
    if (method_info == nullptr || instance == nullptr || color == nullptr ||
        g_runtime_invoke == nullptr || g_object_unbox == nullptr) {
        return false;
    }
    void *exception = nullptr;
    void *boxed = g_runtime_invoke(method_info, instance, nullptr, &exception);
    void *unboxed = exception == nullptr && boxed != nullptr ?
            g_object_unbox(boxed) : nullptr;
    if (unboxed == nullptr) return false;
    memcpy(color, unboxed, sizeof(float) * 4);
    return true;
}

void invoke_color_setter(const void *method_info, void *instance,
                         const float color[4]) {
    if (method_info == nullptr || instance == nullptr || color == nullptr ||
        g_runtime_invoke == nullptr) {
        return;
    }
    void *args[] = {const_cast<float *>(color)};
    void *exception = nullptr;
    g_runtime_invoke(method_info, instance, args, &exception);
}

bool invoke_vertex_gradient_getter(
        const void *method_info, void *instance, float gradient[16]) {
    if (method_info == nullptr || instance == nullptr || gradient == nullptr ||
        g_runtime_invoke == nullptr || g_object_unbox == nullptr) {
        return false;
    }
    void *exception = nullptr;
    void *boxed = g_runtime_invoke(method_info, instance, nullptr, &exception);
    void *unboxed = exception == nullptr && boxed != nullptr ?
            g_object_unbox(boxed) : nullptr;
    if (unboxed == nullptr) return false;
    memcpy(gradient, unboxed, sizeof(float) * 16);
    return true;
}

void invoke_vertex_gradient_setter(
        const void *method_info, void *instance, const float gradient[16]) {
    if (method_info == nullptr || instance == nullptr || gradient == nullptr ||
        g_runtime_invoke == nullptr) {
        return;
    }
    void *args[] = {const_cast<float *>(gradient)};
    void *exception = nullptr;
    g_runtime_invoke(method_info, instance, args, &exception);
}

bool invoke_bool_getter(const void *method_info, void *instance, bool *value) {
    if (method_info == nullptr || instance == nullptr || value == nullptr ||
        g_runtime_invoke == nullptr || g_object_unbox == nullptr) {
        return false;
    }
    void *exception = nullptr;
    void *boxed = g_runtime_invoke(method_info, instance, nullptr, &exception);
    void *unboxed = exception == nullptr && boxed != nullptr ? g_object_unbox(boxed) : nullptr;
    if (unboxed == nullptr) return false;
    uint8_t raw = 0;
    memcpy(&raw, unboxed, sizeof(raw));
    *value = raw != 0;
    return true;
}

bool invoke_font_has_character(const void *method_info, void *font, int32_t character) {
    if (method_info == nullptr || font == nullptr || g_runtime_invoke == nullptr ||
        g_object_unbox == nullptr) {
        return false;
    }
    void *args[] = {&character};
    void *exception = nullptr;
    void *boxed = g_runtime_invoke(method_info, font, args, &exception);
    void *unboxed = exception == nullptr && boxed != nullptr ? g_object_unbox(boxed) : nullptr;
    if (unboxed == nullptr) return false;
    uint8_t raw = 0;
    memcpy(&raw, unboxed, sizeof(raw));
    return raw != 0;
}

bool is_cjk_display_character(char16_t character) {
    return (character >= 0x3040 && character <= 0x30ff) ||
           (character >= 0x31f0 && character <= 0x31ff) ||
           (character >= 0x3400 && character <= 0x4dbf) ||
           (character >= 0x4e00 && character <= 0x9fff) ||
           (character >= 0xf900 && character <= 0xfaff) ||
           (character >= 0xff66 && character <= 0xff9f);
}

/**
 * Returns true when the original component font would have to use a per-glyph
 * fallback for this CJK display string. This is the signature of a dynamically
 * composed translation that was not present in the exact translated-string
 * registry: shared Han characters stay in the Japanese font while simplified-
 * only characters fall through to the appended Chinese fallback.
 */
bool text_requires_chinese_primary_font(void *font, const std::string &text) {
    if (font == nullptr || g_tmp_font_has_character_info == nullptr ||
        g_runtime_invoke == nullptr || g_object_unbox == nullptr) {
        return false;
    }
    std::u16string utf16;
    if (!utf8_to_utf16(text, &utf16)) return false;
    size_t checked = 0;
    for (char16_t character : utf16) {
        if (!is_cjk_display_character(character)) continue;
        if (!invoke_font_has_character(
                g_tmp_font_has_character_info, font, static_cast<int32_t>(character))) {
            return true;
        }
        // Formatted story text can be very long. A mixed-font CJK run exposes a
        // missing glyph well before this limit in every current UI layout.
        if (++checked >= 128) break;
    }
    return false;
}

size_t preload_cjk_glyphs(void *font, const std::string &text) {
    if (font == nullptr || (g_tmp_font_has_character_info == nullptr &&
                            g_tmp_font_try_add_characters_info == nullptr)) return 0;
    if (g_tmp_font_try_add_characters_info != nullptr && g_runtime_invoke != nullptr &&
        g_object_unbox != nullptr) {
        void *managed_text = new_managed_string(text);
        if (managed_text != nullptr) {
            uint8_t include_fallbacks = 0;
            void *args[] = {managed_text, &include_fallbacks};
            void *exception = nullptr;
            void *boxed = g_runtime_invoke(
                    g_tmp_font_try_add_characters_info, font, args, &exception);
            if (exception == nullptr && boxed != nullptr) {
                uint8_t added = 0;
                memcpy(&added, g_object_unbox(boxed), sizeof(added));
                if (added != 0) {
                    apply_chinese_font_materials_for(font);
                    return 0;
                }
            }
        }
    }
    std::u16string utf16;
    if (!utf8_to_utf16(text, &utf16)) return 0;
    size_t checked = 0;
    size_t added = 0;
    for (char16_t character : utf16) {
        if (!is_cjk_display_character(character)) continue;
        bool present = invoke_font_has_character(
                g_tmp_font_has_character_info, font,
                static_cast<int32_t>(character));
        if (!present) ++added;
        if (++checked >= 256) break;
    }
    return added;
}

bool force_tmp_mesh_update(void *instance) {
    if (instance == nullptr || g_tmp_force_mesh_update_info == nullptr ||
        g_runtime_invoke == nullptr) {
        return false;
    }
    void *exception = nullptr;
    if (g_tmp_force_mesh_update_arg_count >= 2) {
        uint8_t ignore_active_state = 1;
        uint8_t force_text_reparsing = 1;
        void *args[] = {&ignore_active_state, &force_text_reparsing};
        g_runtime_invoke(g_tmp_force_mesh_update_info, instance, args, &exception);
    } else if (g_tmp_force_mesh_update_arg_count == 1) {
        uint8_t ignore_active_state = 1;
        void *args[] = {&ignore_active_state};
        g_runtime_invoke(g_tmp_force_mesh_update_info, instance, args, &exception);
    } else {
        g_runtime_invoke(g_tmp_force_mesh_update_info, instance, nullptr, &exception);
    }
    return exception == nullptr;
}

void invoke_bool_setter(const void *method_info, void *instance, bool value) {
    if (method_info == nullptr || instance == nullptr || g_runtime_invoke == nullptr) return;
    uint8_t raw = value ? 1 : 0;
    void *args[] = {&raw};
    void *exception = nullptr;
    g_runtime_invoke(method_info, instance, args, &exception);
}

bool invoke_int_getter(const void *method_info, void *instance, int32_t *value) {
    if (method_info == nullptr || instance == nullptr || value == nullptr ||
        g_runtime_invoke == nullptr || g_object_unbox == nullptr) {
        return false;
    }
    void *exception = nullptr;
    void *boxed = g_runtime_invoke(method_info, instance, nullptr, &exception);
    void *unboxed = exception == nullptr && boxed != nullptr ? g_object_unbox(boxed) : nullptr;
    if (unboxed == nullptr) return false;
    memcpy(value, unboxed, sizeof(*value));
    return true;
}

void invoke_int_setter(const void *method_info, void *instance, int32_t value) {
    if (method_info == nullptr || instance == nullptr || g_runtime_invoke == nullptr) return;
    void *args[] = {&value};
    void *exception = nullptr;
    g_runtime_invoke(method_info, instance, args, &exception);
}

void *invoke_object_getter(const void *method_info, void *instance) {
    if (method_info == nullptr || instance == nullptr || g_runtime_invoke == nullptr) return nullptr;
    void *exception = nullptr;
    void *result = g_runtime_invoke(method_info, instance, nullptr, &exception);
    return exception == nullptr ? result : nullptr;
}

void invoke_object_setter(const void *method_info, void *instance, void *value) {
    if (method_info == nullptr || instance == nullptr || g_runtime_invoke == nullptr) return;
    void *args[] = {value};
    void *exception = nullptr;
    g_runtime_invoke(method_info, instance, args, &exception);
}

struct ComponentRect {
    float width = 0.0f;
    float height = 0.0f;
    bool valid = false;
};

ComponentRect read_component_rect(void *instance, const void *get_rect_transform_info) {
    ComponentRect result;
    if (instance == nullptr || get_rect_transform_info == nullptr ||
        g_rect_transform_get_rect_info == nullptr || g_runtime_invoke == nullptr ||
        g_object_unbox == nullptr) {
        return result;
    }
    void *rect_transform = invoke_object_getter(get_rect_transform_info, instance);
    if (rect_transform == nullptr) return result;
    void *exception = nullptr;
    void *boxed_rect = g_runtime_invoke(
            g_rect_transform_get_rect_info, rect_transform, nullptr, &exception);
    void *unboxed_rect = exception == nullptr && boxed_rect != nullptr ?
            g_object_unbox(boxed_rect) : nullptr;
    if (unboxed_rect == nullptr) return result;
    // UnityEngine.Rect 的顺序为 x、y、width、height；这里只读取排版需要的后两个值。
    float rect_values[4] = {};
    memcpy(rect_values, unboxed_rect, sizeof(rect_values));
    result.width = rect_values[2];
    result.height = rect_values[3];
    result.valid = result.width > 0.0f && result.height > 0.0f &&
            result.width < 100000.0f && result.height < 100000.0f;
    return result;
}

std::string read_unity_object_name(void *instance) {
    std::string name;
    void *managed_name = invoke_object_getter(g_object_get_name_info, instance);
    if (managed_name == nullptr || g_string_length == nullptr || g_string_chars == nullptr) {
        return name;
    }
    int32_t length = g_string_length(managed_name);
    if (length < 0 || length > 1024 ||
        !utf16_to_utf8(g_string_chars(managed_name), length, &name)) {
        name.clear();
    }
    return name;
}

std::string read_managed_string_value(void *managed_text) {
    std::string value;
    if (managed_text == nullptr || g_string_length == nullptr ||
        g_string_chars == nullptr) {
        return value;
    }
    int32_t length = g_string_length(managed_text);
    if (length < 0 || length > 1024 ||
        !utf16_to_utf8(g_string_chars(managed_text), length, &value)) {
        value.clear();
    }
    return value;
}

std::string read_unity_parent_name(void *component) {
    void *transform = invoke_object_getter(g_component_get_transform_info, component);
    void *parent = invoke_object_getter(g_transform_get_parent_info, transform);
    return read_unity_object_name(parent);
}

std::vector<std::string> read_unity_ancestor_names(void *component, int32_t max_depth) {
    std::vector<std::string> names;
    void *current = invoke_object_getter(g_component_get_transform_info, component);
    for (int32_t depth = 0; depth < max_depth && current != nullptr; ++depth) {
        current = invoke_object_getter(g_transform_get_parent_info, current);
        if (current == nullptr) break;
        std::string name = read_unity_object_name(current);
        if (!name.empty()) names.push_back(name);
    }
    return names;
}

void log_short_text_diagnostic(const char *kind, void *instance, void *source_text,
                               void *replacement, bool translated) {
    if (kind == nullptr || instance == nullptr || g_string_length == nullptr ||
        g_string_chars == nullptr) return;
    int32_t source_length = source_text == nullptr ? -1 : g_string_length(source_text);
    int32_t result_length = replacement == nullptr ? -1 : g_string_length(replacement);
    std::string source;
    std::string result;
    if (source_length >= 0 && source_length <= 4096) {
        utf16_to_utf8(g_string_chars(source_text), source_length, &source);
    }
    if (result_length >= 0 && result_length <= 4096) {
        utf16_to_utf8(g_string_chars(replacement), result_length, &result);
    }
    static const char *const navigation_labels[] = {
            "罪人", "玻璃窗", "驾驶席", "放映室", "提取", "自动贩卖机"
    };
    bool navigation_text = std::any_of(
            std::begin(navigation_labels), std::end(navigation_labels),
            [&](const char *label) {
                return source == label || result == label;
            });
    bool empty_result = result_length <= 0;
    bool support_mapping = source == "サポート" || result == "助战" || result == "支援";
    bool wing_mapping = source == "翼" || source == "Wings" ||
            result == "翼" || result == "翅膀";
    if (!navigation_text && !empty_result && !support_mapping && !wing_mapping) return;
    uint64_t hits = g_short_text_diagnostics.fetch_add(1, std::memory_order_relaxed) + 1;
    if (hits > 240) return;
    std::string name = read_unity_object_name(instance);
    std::string parent = read_unity_parent_name(instance);
    LT_LOGI("Short text diagnostic %s instance=%p sourceChars=%d resultChars=%d"
            " translated=%d empty=%d support=%d wing=%d name=%s parent=%s source=%s result=%s",
            kind, instance, source_length, result_length, translated ? 1 : 0,
            empty_result ? 1 : 0, support_mapping ? 1 : 0, wing_mapping ? 1 : 0,
            name.c_str(), parent.c_str(),
            source.c_str(), result.c_str());
}

bool is_navigation_managed_text(void *managed_text) {
    if (managed_text == nullptr || g_string_length == nullptr || g_string_chars == nullptr) {
        return false;
    }
    int32_t length = g_string_length(managed_text);
    std::string text;
    if (length < 0 || length > 16 ||
        !utf16_to_utf8(g_string_chars(managed_text), length, &text)) {
        return false;
    }
    static const char *const labels[] = {
            "罪人", "玻璃窗", "驾驶席", "放映室", "提取", "自动贩卖机"
    };
    return std::any_of(std::begin(labels), std::end(labels),
                       [&](const char *label) { return text == label; });
}

struct MaterialOutlineStyle {
    float width = 0.0f;
    // 只记录中文材质自身的字面补偿，不读取或复制日文材质的膨胀设置。
    float face_dilate = 0.0f;
    float color[4] = {0.0f, 0.0f, 0.0f, 1.0f};
    bool has_width = false;
    bool has_face_dilate = false;
    bool has_color = false;
};

bool read_material_float_property(void *material, const char *property, float *value) {
    if (material == nullptr || property == nullptr || value == nullptr ||
        g_material_get_float_info == nullptr || g_runtime_invoke == nullptr ||
        g_object_unbox == nullptr) {
        return false;
    }
    void *property_name = new_managed_string(property);
    if (property_name == nullptr) return false;
    void *args[] = {property_name};
    void *exception = nullptr;
    void *boxed = g_runtime_invoke(
            g_material_get_float_info, material, args, &exception);
    void *unboxed = exception == nullptr && boxed != nullptr ?
            g_object_unbox(boxed) : nullptr;
    if (unboxed == nullptr) return false;
    memcpy(value, unboxed, sizeof(*value));
    return true;
}

void write_material_float_property(void *material, const char *property, float value) {
    if (material == nullptr || property == nullptr ||
        g_material_set_float_info == nullptr || g_runtime_invoke == nullptr) {
        return;
    }
    void *property_name = new_managed_string(property);
    if (property_name == nullptr) return;
    void *args[] = {property_name, &value};
    void *exception = nullptr;
    g_runtime_invoke(g_material_set_float_info, material, args, &exception);
}

bool read_material_color_property(void *material, const char *property, float color[4]) {
    if (material == nullptr || property == nullptr || color == nullptr ||
        g_material_get_color_info == nullptr || g_runtime_invoke == nullptr ||
        g_object_unbox == nullptr) {
        return false;
    }
    void *property_name = new_managed_string(property);
    if (property_name == nullptr) return false;
    void *args[] = {property_name};
    void *exception = nullptr;
    void *boxed = g_runtime_invoke(
            g_material_get_color_info, material, args, &exception);
    void *unboxed = exception == nullptr && boxed != nullptr ?
            g_object_unbox(boxed) : nullptr;
    if (unboxed == nullptr) return false;
    memcpy(color, unboxed, sizeof(float) * 4);
    return true;
}

void write_material_color_property(void *material, const char *property,
                                   const float color[4]) {
    if (material == nullptr || property == nullptr || color == nullptr ||
        g_material_set_color_info == nullptr || g_runtime_invoke == nullptr) {
        return;
    }
    void *property_name = new_managed_string(property);
    if (property_name == nullptr) return;
    void *args[] = {property_name, const_cast<float *>(color)};
    void *exception = nullptr;
    g_runtime_invoke(g_material_set_color_info, material, args, &exception);
}

void *read_material_texture_property(void *material, const char *property) {
    if (material == nullptr || property == nullptr ||
        g_material_get_texture_info == nullptr || g_runtime_invoke == nullptr) {
        return nullptr;
    }
    void *property_name = new_managed_string(property);
    if (property_name == nullptr) return nullptr;
    void *args[] = {property_name};
    void *exception = nullptr;
    void *texture = g_runtime_invoke(
            g_material_get_texture_info, material, args, &exception);
    return exception == nullptr ? texture : nullptr;
}

void write_material_texture_property(
        void *material, const char *property, void *texture) {
    if (material == nullptr || property == nullptr || texture == nullptr ||
        g_material_set_texture_info == nullptr || g_runtime_invoke == nullptr) {
        return;
    }
    void *property_name = new_managed_string(property);
    if (property_name == nullptr) return;
    void *args[] = {property_name, texture};
    void *exception = nullptr;
    g_runtime_invoke(g_material_set_texture_info, material, args, &exception);
}

bool read_material_keyword_property(void *material, const char *keyword, bool *enabled) {
    if (material == nullptr || keyword == nullptr || enabled == nullptr ||
        g_material_is_keyword_enabled_info == nullptr || g_runtime_invoke == nullptr ||
        g_object_unbox == nullptr) {
        return false;
    }
    void *keyword_name = new_managed_string(keyword);
    if (keyword_name == nullptr) return false;
    void *args[] = {keyword_name};
    void *exception = nullptr;
    void *boxed = g_runtime_invoke(
            g_material_is_keyword_enabled_info, material, args, &exception);
    void *unboxed = exception == nullptr && boxed != nullptr ?
            g_object_unbox(boxed) : nullptr;
    if (unboxed == nullptr) return false;
    uint8_t value = 0;
    memcpy(&value, unboxed, sizeof(value));
    *enabled = value != 0;
    return true;
}

void write_material_keyword_property(void *material, const char *keyword, bool enabled) {
    if (material == nullptr || keyword == nullptr || g_runtime_invoke == nullptr) {
        return;
    }
    const void *method_info = enabled ? g_material_enable_keyword_info :
            g_material_disable_keyword_info;
    if (method_info == nullptr) return;
    void *keyword_name = new_managed_string(keyword);
    if (keyword_name == nullptr) return;
    void *args[] = {keyword_name};
    void *exception = nullptr;
    g_runtime_invoke(method_info, material, args, &exception);
}

MaterialVisualStyle read_material_visual_style(void *material) {
    MaterialVisualStyle style;
    if (material == nullptr) return style;
    style.has_face_dilate = read_material_float_property(
            material, "_FaceDilate", &style.face_dilate);
    style.has_outline_width = read_material_float_property(
            material, "_OutlineWidth", &style.outline_width);
    style.has_outline_softness = read_material_float_property(
            material, "_OutlineSoftness", &style.outline_softness);
    style.has_underlay_offset_x = read_material_float_property(
            material, "_UnderlayOffsetX", &style.underlay_offset_x);
    style.has_underlay_offset_y = read_material_float_property(
            material, "_UnderlayOffsetY", &style.underlay_offset_y);
    style.has_underlay_dilate = read_material_float_property(
            material, "_UnderlayDilate", &style.underlay_dilate);
    style.has_underlay_softness = read_material_float_property(
            material, "_UnderlaySoftness", &style.underlay_softness);
    style.has_gradient_scale = read_material_float_property(
            material, "_GradientScale", &style.gradient_scale);
    style.has_scale_ratio_a = read_material_float_property(
            material, "_ScaleRatioA", &style.scale_ratio_a);
    style.has_scale_ratio_b = read_material_float_property(
            material, "_ScaleRatioB", &style.scale_ratio_b);
    style.has_scale_ratio_c = read_material_float_property(
            material, "_ScaleRatioC", &style.scale_ratio_c);
    style.has_texture_width = read_material_float_property(
            material, "_TextureWidth", &style.texture_width);
    style.has_texture_height = read_material_float_property(
            material, "_TextureHeight", &style.texture_height);
    style.has_face_color = read_material_color_property(
            material, "_FaceColor", style.face_color);
    style.has_outline_color = read_material_color_property(
            material, "_OutlineColor", style.outline_color);
    style.has_underlay_color = read_material_color_property(
            material, "_UnderlayColor", style.underlay_color);
    return style;
}

float material_effect_ratio(
        const MaterialVisualStyle &source, const MaterialVisualStyle &target,
        bool underlay) {
    const float source_scale = source.gradient_scale *
            (underlay ? source.scale_ratio_b : source.scale_ratio_a);
    const float target_scale = target.gradient_scale *
            (underlay ? target.scale_ratio_b : target.scale_ratio_a);
    if (!std::isfinite(source_scale) || !std::isfinite(target_scale) ||
        source_scale <= 0.0001f || target_scale <= 0.0001f) {
        return 1.0f;
    }
    return source_scale / target_scale;
}

void apply_material_visual_style(
        void *material, const MaterialVisualStyle &style,
        float face_dilate = limbus_text_layout::kChineseFaceDilate,
        bool preserve_source_outline = false,
        bool exact_source_style = false) {
    if (material == nullptr) return;
    // Battle state materials are cloned from the source material and keep its
    // complete shader configuration. Only _MainTex is replaced with the
    // Chinese SDF atlas, so source-authored outline and underlay values remain
    // in the same coordinate system as the original Japanese text.
    const float transparent_black[4] = {0.0f, 0.0f, 0.0f, 0.0f};
    float outline_ratio = 1.0f;
    float underlay_ratio = 1.0f;
    if (preserve_source_outline) {
        if (!exact_source_style) {
            const MaterialVisualStyle target_style =
                    read_material_visual_style(material);
            outline_ratio = material_effect_ratio(
                    style, target_style, false);
            underlay_ratio = material_effect_ratio(
                    style, target_style, true);
        }
        const float applied_face_dilate = style.has_face_dilate ?
                style.face_dilate * outline_ratio : 0.0f;
        const float applied_outline_width = style.has_outline_width ?
                style.outline_width * outline_ratio : 0.0f;
        write_material_float_property(
                material, "_FaceDilate", applied_face_dilate);
        write_material_float_property(
                material, "_OutlineWidth", applied_outline_width);
        write_material_float_property(
                material, "_OutlineSoftness",
                style.has_outline_softness ? style.outline_softness : 0.0f);
        if (exact_source_style) {
            if (style.has_gradient_scale) {
                write_material_float_property(
                        material, "_GradientScale", style.gradient_scale);
            }
            if (style.has_scale_ratio_a) {
                write_material_float_property(
                        material, "_ScaleRatioA", style.scale_ratio_a);
            }
            if (style.has_scale_ratio_b) {
                write_material_float_property(
                        material, "_ScaleRatioB", style.scale_ratio_b);
            }
            if (style.has_scale_ratio_c) {
                write_material_float_property(
                        material, "_ScaleRatioC", style.scale_ratio_c);
            }
            if (style.has_texture_width) {
                write_material_float_property(
                        material, "_TextureWidth", style.texture_width);
            }
            if (style.has_texture_height) {
                write_material_float_property(
                        material, "_TextureHeight", style.texture_height);
            }
        }
        float outline_color[4];
        std::copy(style.outline_color, style.outline_color + 4, outline_color);
        write_material_color_property(material, "_OutlineColor", outline_color);
        const bool outline_enabled =
                applied_outline_width > 0.001f && style.has_outline_color &&
                style.outline_color[3] > 0.001f;
        write_material_keyword_property(material, "OUTLINE_ON", outline_enabled);
        write_material_keyword_property(material, "OUTLINE_OFF", !outline_enabled);
    } else {
        write_material_float_property(material, "_FaceDilate", face_dilate);
        write_material_float_property(
                material, "_OutlineWidth", limbus_text_layout::kChineseOutlineWidth);
        write_material_float_property(material, "_OutlineSoftness", 0.0f);
        write_material_color_property(material, "_OutlineColor", transparent_black);
        write_material_keyword_property(material, "OUTLINE_ON", false);
        write_material_keyword_property(material, "OUTLINE_OFF", true);
    }
    if (preserve_source_outline) {
        if (style.has_underlay_offset_x) {
            write_material_float_property(
                    material, "_UnderlayOffsetX",
                    style.underlay_offset_x * underlay_ratio);
        }
        if (style.has_underlay_offset_y) {
            write_material_float_property(
                    material, "_UnderlayOffsetY",
                    style.underlay_offset_y * underlay_ratio);
        }
        write_material_float_property(
                material, "_UnderlayDilate",
                style.has_underlay_dilate ?
                        style.underlay_dilate * underlay_ratio :
                        limbus_text_layout::kChineseUnderlayDilate);
        write_material_float_property(
                material, "_UnderlaySoftness",
                style.has_underlay_softness ?
                        style.underlay_softness : 0.0f);
        if (style.has_underlay_color) {
            write_material_color_property(
                    material, "_UnderlayColor", style.underlay_color);
        } else {
            write_material_color_property(material, "_UnderlayColor", transparent_black);
        }
        bool underlay_enabled = style.has_underlay_color &&
                style.underlay_color[3] > 0.001f;
        write_material_keyword_property(material, "UNDERLAY_ON", underlay_enabled);
        write_material_keyword_property(material, "UNDERLAY_OFF", !underlay_enabled);
    } else {
        const float offset_x = (style.has_underlay_offset_x &&
                std::abs(style.underlay_offset_x) >= 0.08f) ?
                limbus_text_layout::cjk_underlay_offset(style.underlay_offset_x) :
                limbus_text_layout::kChineseTitleUnderlayOffsetX;
        const float offset_y = (style.has_underlay_offset_y &&
                std::abs(style.underlay_offset_y) >= 0.10f) ?
                limbus_text_layout::cjk_underlay_offset(style.underlay_offset_y) :
                limbus_text_layout::kChineseTitleUnderlayOffsetY;
        write_material_float_property(material, "_UnderlayOffsetX", offset_x);
        write_material_float_property(material, "_UnderlayOffsetY", offset_y);
        write_material_float_property(
                material, "_UnderlayDilate", limbus_text_layout::kChineseUnderlayDilate);
        write_material_float_property(material, "_UnderlaySoftness", 0.0f);
        const float black_underlay[4] = {
                0.0f, 0.0f, 0.0f, limbus_text_layout::kChineseUnderlayAlpha
        };
        write_material_color_property(material, "_UnderlayColor", black_underlay);
        write_material_keyword_property(material, "UNDERLAY_ON", true);
        write_material_keyword_property(material, "UNDERLAY_OFF", false);
    }
    if (style.has_face_color && style.face_color[3] > 0.001f) {
        write_material_color_property(material, "_FaceColor", style.face_color);
    } else if (!preserve_source_outline) {
        float white[4] = {1.0f, 1.0f, 1.0f, 1.0f};
        write_material_color_property(material, "_FaceColor", white);
    }
}

MaterialOutlineStyle read_material_outline(void *material) {
    MaterialOutlineStyle style;
    if (material == nullptr || g_runtime_invoke == nullptr || g_object_unbox == nullptr) {
        return style;
    }
    void *width_name = new_managed_string("_OutlineWidth");
    void *dilate_name = new_managed_string("_FaceDilate");
    void *color_name = new_managed_string("_OutlineColor");
    if (width_name != nullptr && g_material_get_float_info != nullptr) {
        void *args[] = {width_name};
        void *exception = nullptr;
        void *boxed = g_runtime_invoke(g_material_get_float_info, material, args, &exception);
        void *unboxed = exception == nullptr && boxed != nullptr ? g_object_unbox(boxed) : nullptr;
        if (unboxed != nullptr) {
            memcpy(&style.width, unboxed, sizeof(style.width));
            style.has_width = true;
        }
    }
    if (dilate_name != nullptr && g_material_get_float_info != nullptr) {
        void *args[] = {dilate_name};
        void *exception = nullptr;
        void *boxed = g_runtime_invoke(g_material_get_float_info, material, args, &exception);
        void *unboxed = exception == nullptr && boxed != nullptr ? g_object_unbox(boxed) : nullptr;
        if (unboxed != nullptr) {
            memcpy(&style.face_dilate, unboxed, sizeof(style.face_dilate));
            style.has_face_dilate = true;
        }
    }
    if (color_name != nullptr && g_material_get_color_info != nullptr) {
        void *args[] = {color_name};
        void *exception = nullptr;
        void *boxed = g_runtime_invoke(g_material_get_color_info, material, args, &exception);
        void *unboxed = exception == nullptr && boxed != nullptr ? g_object_unbox(boxed) : nullptr;
        if (unboxed != nullptr) {
            memcpy(style.color, unboxed, sizeof(style.color));
            style.has_color = true;
        }
    }
    return style;
}

void apply_material_outline(void *material, const MaterialOutlineStyle &style) {
    if (material == nullptr || !style.has_width || style.width <= 0.0001f ||
        g_runtime_invoke == nullptr) {
        return;
    }
    void *width_name = new_managed_string("_OutlineWidth");
    void *dilate_name = new_managed_string("_FaceDilate");
    void *color_name = new_managed_string("_OutlineColor");
    if (style.has_face_dilate && dilate_name != nullptr && g_material_set_float_info != nullptr) {
        // TMP 的填充阈值同时受描边宽度和字面膨胀影响；等宽补偿避免细笔画被黑边侵蚀。
        float dilate = style.face_dilate;
        void *args[] = {dilate_name, &dilate};
        void *exception = nullptr;
        g_runtime_invoke(g_material_set_float_info, material, args, &exception);
    }
    if (width_name != nullptr && g_material_set_float_info != nullptr) {
        float width = style.width;
        void *args[] = {width_name, &width};
        void *exception = nullptr;
        g_runtime_invoke(g_material_set_float_info, material, args, &exception);
    }
    if (color_name != nullptr && g_material_set_color_info != nullptr) {
        // 保持中文 SDF 自身的材质与图集，只设置黑色描边，不复制日文字体的膨胀参数。
        float black[4] = {0.0f, 0.0f, 0.0f,
                          style.has_color ? std::max(style.color[3], 0.95f) : 1.0f};
        void *args[] = {color_name, black};
        void *exception = nullptr;
        g_runtime_invoke(g_material_set_color_info, material, args, &exception);
    }
}

const void *find_inherited_runtime_method(void *instance, const char *name, int arg_count) {
    if (instance == nullptr || name == nullptr || g_object_get_class == nullptr ||
        g_class_get_parent == nullptr || g_class_get_method == nullptr) {
        return nullptr;
    }
    for (void *klass = g_object_get_class(instance); klass != nullptr;
         klass = g_class_get_parent(klass)) {
        const void *method = g_class_get_method(klass, name, arg_count);
        if (method != nullptr) return method;
    }
    return nullptr;
}

bool read_collection_count(void *collection, int32_t *count) {
    if (collection == nullptr || count == nullptr) return false;
    const void *length_method = find_inherited_runtime_method(collection, "get_Length", 0);
    if (length_method == nullptr) {
        length_method = find_inherited_runtime_method(collection, "get_Count", 0);
    }
    return length_method != nullptr && invoke_int_getter(length_method, collection, count) &&
            *count >= 0;
}

void *read_collection_item(void *collection, int32_t index) {
    if (collection == nullptr || index < 0) return nullptr;
    const void *item_method = find_inherited_runtime_method(collection, "GetValue", 1);
    if (item_method == nullptr) {
        item_method = find_inherited_runtime_method(collection, "get_Item", 1);
    }
    void *item = nullptr;
    if (item_method != nullptr && g_runtime_invoke != nullptr) {
        void *args[] = {&index};
        void *exception = nullptr;
        item = g_runtime_invoke(item_method, collection, args, &exception);
        if (exception != nullptr) item = nullptr;
    }
    if (item != nullptr) return item;
    // TMP's fontMaterials getter commonly returns a Material[] whose inherited
    // Array.GetValue wrapper is not always exposed through IL2CPP metadata.
    // Read the object-array vector directly in that case.
    bool array_like = find_inherited_runtime_method(collection, "get_Length", 0) != nullptr;
    if (!array_like) return nullptr;
    void *address = g_array_addr_with_size == nullptr ? nullptr :
            g_array_addr_with_size(
                    collection, static_cast<int32_t>(sizeof(void *)),
                    static_cast<uint32_t>(index));
    if (address == nullptr) {
        // IL2CPP's object-array ABI is stable on this arm64 runtime:
        // klass, monitor, bounds, max_length, vector[]. This fallback is only
        // used after confirming the object exposes Array.get_Length.
        constexpr size_t kIl2CppArrayVectorOffset = sizeof(void *) * 4;
        address = reinterpret_cast<unsigned char *>(collection) +
                kIl2CppArrayVectorOffset + static_cast<size_t>(index) * sizeof(void *);
    }
    if (address == nullptr) return nullptr;
    memcpy(&item, address, sizeof(item));
    return item;
}

void *read_named_object_field(void *instance, const char *const *names, size_t name_count) {
    if (instance == nullptr || names == nullptr || name_count == 0 ||
        g_object_get_class == nullptr || g_class_get_parent == nullptr ||
        g_class_get_fields == nullptr || g_field_get_name == nullptr ||
        g_field_get_value == nullptr) {
        return nullptr;
    }
    for (void *klass = g_object_get_class(instance); klass != nullptr;
         klass = g_class_get_parent(klass)) {
        void *iterator = nullptr;
        const void *field = nullptr;
        while ((field = g_class_get_fields(klass, &iterator)) != nullptr) {
            const char *field_name = g_field_get_name(field);
            if (field_name == nullptr) continue;
            for (size_t index = 0; index < name_count; ++index) {
                if (strcmp(field_name, names[index]) != 0) continue;
                void *value = nullptr;
                g_field_get_value(instance, field, &value);
                return value;
            }
        }
    }
    return nullptr;
}

void clear_tmp_font_fallback_table(void *asset) {
    if (asset == nullptr || g_object_get_class == nullptr ||
        g_class_get_method == nullptr || g_runtime_invoke == nullptr) {
        return;
    }
    static const char *const fields[] = {
            "m_FallbackFontAssetTable", "fallbackFontAssetTable"
    };
    void *fallbacks = read_named_object_field(
            asset, fields, sizeof(fields) / sizeof(fields[0]));
    if (fallbacks == nullptr) return;
    const void *clear_method = g_class_get_method(
            g_object_get_class(fallbacks), "Clear", 0);
    if (clear_method != nullptr) {
        void *exception = nullptr;
        g_runtime_invoke(clear_method, fallbacks, nullptr, &exception);
        LT_LOGI("Cleared TMP font asset fallback table asset=%p exception=%p",
                asset, exception);
    }
}

void prepend_tmp_font_fallback(void *asset, void *chinese_font) {
    if (asset == nullptr || chinese_font == nullptr ||
        asset == g_tmp_font_asset || asset == g_tmp_regular_font_asset ||
        g_object_get_class == nullptr || g_class_get_method == nullptr ||
        g_runtime_invoke == nullptr) {
        return;
    }
    static const char *const fields[] = {
            "m_FallbackFontAssetTable", "fallbackFontAssetTable"
    };
    void *fallbacks = read_named_object_field(
            asset, fields, sizeof(fields) / sizeof(fields[0]));
    if (fallbacks == nullptr) return;
    ManagedObjectPin fallbacks_pin(fallbacks);
    if (!fallbacks_pin.alive()) return;
    int32_t count = 0;
    if (read_collection_count(fallbacks, &count) && count > 0) {
        void *first = read_collection_item(fallbacks, 0);
        if (first == chinese_font) return;
    }
    std::vector<void *> existing;
    int32_t bounded_count = std::min(std::max(count, 0), 16);
    existing.reserve(static_cast<size_t>(bounded_count));
    for (int32_t i = 0; i < bounded_count; ++i) {
        void *item = read_collection_item(fallbacks, i);
        if (item != nullptr && item != chinese_font &&
            item != g_tmp_font_asset && item != g_tmp_regular_font_asset) {
            existing.push_back(item);
        }
    }
    void *list_class = g_object_get_class(fallbacks);
    const void *clear_method = list_class == nullptr ? nullptr :
            g_class_get_method(list_class, "Clear", 0);
    const void *add_method = list_class == nullptr ? nullptr :
            g_class_get_method(list_class, "Add", 1);
    if (clear_method == nullptr || add_method == nullptr) return;
    void *exception = nullptr;
    g_runtime_invoke(clear_method, fallbacks, nullptr, &exception);
    if (exception != nullptr) return;
    void *primary_args[] = {chinese_font};
    g_runtime_invoke(add_method, fallbacks, primary_args, &exception);
    if (g_tmp_regular_font_asset != nullptr && g_tmp_regular_font_asset != chinese_font) {
        void *regular_args[] = {g_tmp_regular_font_asset};
        void *reg_exception = nullptr;
        g_runtime_invoke(add_method, fallbacks, regular_args, &reg_exception);
    }
    for (void *item : existing) {
        void *item_args[] = {item};
        void *item_exception = nullptr;
        g_runtime_invoke(add_method, fallbacks, item_args, &item_exception);
    }
    LT_LOGI("Prepended Chinese TMP fallback to source font=%p (%s) previousCount=%d",
            asset, read_unity_object_name(asset).c_str(), count);
}

void *read_font_material_collection(void *asset) {
    void *materials = invoke_object_getter(
            g_tmp_font_get_materials_info, asset);
    if (materials != nullptr) return materials;
    static const char *const fields[] = {
            "m_MaterialArray", "m_Materials", "m_materialArray"
    };
    return read_named_object_field(
            asset, fields, sizeof(fields) / sizeof(fields[0]));
}

void *read_tmp_material_collection(void *instance) {
    void *published = invoke_object_getter(g_tmp_get_font_materials_info, instance);
    int32_t published_count = 0;
    if (published != nullptr && read_collection_count(published, &published_count) &&
        published_count > 0) {
        return published;
    }
    // The public getter can briefly return an empty view while the mesh is
    // rebuilding. In that window, inspect TMP's actual per-submesh backing list.
    static const char *const fields[] = {
            "m_fontMaterials", "m_fontSharedMaterials", "m_FontMaterials"
    };
    void *materials = read_named_object_field(
            instance, fields, sizeof(fields) / sizeof(fields[0]));
    int32_t material_count = 0;
    if (materials != nullptr && read_collection_count(materials, &material_count) &&
        material_count > 0) {
        return materials;
    }
    return published != nullptr ? published : materials;
}

bool apply_chinese_material_underlay(void *material, bool force) {
    if (material == nullptr || g_runtime_invoke == nullptr) return false;
    ManagedObjectPin material_pin(material);
    if (!material_pin.alive()) {
        uint64_t failures = g_material_pin_failures.fetch_add(
                1, std::memory_order_relaxed) + 1;
        if (failures <= 12 || failures % 250 == 0) {
            LT_LOGW("Skipped invalid TMP material instance=%p failures=%llu",
                    material, static_cast<unsigned long long>(failures));
        }
        return false;
    }
    pthread_mutex_lock(&g_font_state_lock);
    auto styled = g_styled_chinese_materials.find(material);
    bool already_styled = false;
    if (styled != g_styled_chinese_materials.end()) {
        void *live_target = g_gchandle_get_target == nullptr ? nullptr :
                g_gchandle_get_target(styled->second);
        already_styled = live_target == material;
        if (!already_styled) {
            if (g_gchandle_free != nullptr && styled->second != 0) {
                g_gchandle_free(styled->second);
            }
            g_styled_chinese_materials.erase(styled);
        } else if (force) {
            if (g_gchandle_free != nullptr && styled->second != 0) {
                g_gchandle_free(styled->second);
            }
            g_styled_chinese_materials.erase(styled);
            already_styled = false;
        }
    }
    if (already_styled && !force) {
        pthread_mutex_unlock(&g_font_state_lock);
        return false;
    }
    uintptr_t weak_handle = create_weak_object_handle(material);
    if (weak_handle != 0) {
        if (g_styled_chinese_materials.size() >= 4096) {
            if (g_gchandle_free != nullptr) {
                for (const auto &entry : g_styled_chinese_materials) {
                    if (entry.second != 0) g_gchandle_free(entry.second);
                }
            }
            g_styled_chinese_materials.clear();
        }
        g_styled_chinese_materials[material] = weak_handle;
    }
    pthread_mutex_unlock(&g_font_state_lock);

    void *width_name = new_managed_string("_OutlineWidth");
    void *dilate_name = new_managed_string("_FaceDilate");
    void *softness_name = new_managed_string("_OutlineSoftness");
    void *outline_color_name = new_managed_string("_OutlineColor");
    void *underlay_color_name = new_managed_string("_UnderlayColor");
    void *underlay_offset_x_name = new_managed_string("_UnderlayOffsetX");
    void *underlay_offset_y_name = new_managed_string("_UnderlayOffsetY");
    void *underlay_dilate_name = new_managed_string("_UnderlayDilate");
    void *underlay_softness_name = new_managed_string("_UnderlaySoftness");
    // 战斗文本采用 PC 版的白字黑托底。Outline 会向内侵蚀笔画并在小字号下
    // 把相邻字符粘在一起，因此这里明确归零，只修改 Underlay。
    const float outline_width = limbus_text_layout::kChineseOutlineWidth;
    const float face_dilate = limbus_text_layout::kChineseFaceDilate;
    float zero = 0.0f;
    if (dilate_name != nullptr && g_material_set_float_info != nullptr) {
        float value = face_dilate;
        void *args[] = {dilate_name, &value};
        void *exception = nullptr;
        g_runtime_invoke(g_material_set_float_info, material, args, &exception);
    }
    if (width_name != nullptr && g_material_set_float_info != nullptr) {
        float value = outline_width;
        void *args[] = {width_name, &value};
        void *exception = nullptr;
        g_runtime_invoke(g_material_set_float_info, material, args, &exception);
    }
    if (softness_name != nullptr && g_material_set_float_info != nullptr) {
        void *args[] = {softness_name, &zero};
        void *exception = nullptr;
        g_runtime_invoke(g_material_set_float_info, material, args, &exception);
    }
    if (outline_color_name != nullptr && g_material_set_color_info != nullptr) {
        float black[4] = {0.0f, 0.0f, 0.0f, 1.0f};
        void *args[] = {outline_color_name, black};
        void *exception = nullptr;
        g_runtime_invoke(g_material_set_color_info, material, args, &exception);
    }
    // Underlay 绘制在填充层后方，不会改变字形内部的白色笔画。
    if (underlay_color_name != nullptr && g_material_set_color_info != nullptr) {
        float black[4] = {0.0f, 0.0f, 0.0f,
                          limbus_text_layout::kChineseUnderlayAlpha};
        void *args[] = {underlay_color_name, black};
        void *exception = nullptr;
        g_runtime_invoke(g_material_set_color_info, material, args, &exception);
    }
    const float underlay_offset_x = limbus_text_layout::kChineseTitleUnderlayOffsetX;
    const float underlay_offset_y = limbus_text_layout::kChineseTitleUnderlayOffsetY;
    const float underlay_dilate = limbus_text_layout::kChineseUnderlayDilate;
    if (underlay_offset_x_name != nullptr && g_material_set_float_info != nullptr) {
        float value = underlay_offset_x;
        void *args[] = {underlay_offset_x_name, &value};
        void *exception = nullptr;
        g_runtime_invoke(g_material_set_float_info, material, args, &exception);
    }
    if (underlay_offset_y_name != nullptr && g_material_set_float_info != nullptr) {
        float value = underlay_offset_y;
        void *args[] = {underlay_offset_y_name, &value};
        void *exception = nullptr;
        g_runtime_invoke(g_material_set_float_info, material, args, &exception);
    }
    if (underlay_dilate_name != nullptr && g_material_set_float_info != nullptr) {
        float value = underlay_dilate;
        void *args[] = {underlay_dilate_name, &value};
        void *exception = nullptr;
        g_runtime_invoke(g_material_set_float_info, material, args, &exception);
    }
    if (underlay_softness_name != nullptr && g_material_set_float_info != nullptr) {
        void *args[] = {underlay_softness_name, &zero};
        void *exception = nullptr;
        g_runtime_invoke(g_material_set_float_info, material, args, &exception);
    }
    write_material_keyword_property(material, "UNDERLAY_ON", true);
    write_material_keyword_property(material, "UNDERLAY_OFF", false);
    MaterialOutlineStyle applied = read_material_outline(material);
    LT_LOGI("Chinese SDF underlay material=%p width=%.3f faceDilate=%.3f "
            "underlayOffset=(%.3f,%.3f) dilate=%.3f",
            material, applied.width, applied.face_dilate,
            underlay_offset_x, underlay_offset_y, underlay_dilate);
    return true;
}

int32_t apply_chinese_material_collection(void *collection, bool force) {
    int32_t count = 0;
    if (!read_collection_count(collection, &count)) return 0;
    count = std::min(count, 64);
    int32_t styled = 0;
    for (int32_t index = 0; index < count; ++index) {
        void *material = read_collection_item(collection, index);
        if (material == nullptr) continue;
        if (apply_chinese_material_underlay(material, force)) ++styled;
    }
    return styled;
}

void apply_chinese_font_materials_for(void *asset) {
    if (asset == nullptr || g_runtime_invoke == nullptr) return;
    ManagedObjectPin asset_pin(asset);
    if (!asset_pin.alive()) return;
    void *base_material = invoke_object_getter(g_tmp_font_get_material_info, asset);
    if (base_material != nullptr) {
        apply_chinese_material_underlay(base_material, false);
    }
    void *materials = read_font_material_collection(asset);
    if (materials == nullptr) return;
    ManagedObjectPin materials_pin(materials);
    if (!materials_pin.alive()) return;
    int32_t count = 0;
    if (!read_collection_count(materials, &count)) return;
    count = std::min(count, 64);
    int32_t styled = apply_chinese_material_collection(materials, false);
    LT_LOGI("Chinese SDF font materials asset=%p count=%d styled=%d",
            asset, count, styled);
}

void apply_chinese_font_materials() {
    apply_chinese_font_materials_for(g_tmp_font_asset);
    apply_chinese_font_materials_for(g_tmp_regular_font_asset);
}

void sync_translated_tmp_materials(void *instance, bool force = false) {
    (void)force;
    TmpComponentState state;
    if (instance == nullptr || !find_tmp_component_state(instance, &state)) return;
    refresh_tmp_source_material_style(instance, &state);
    const std::string component_name = read_unity_object_name(instance);
    const std::string parent_name = read_unity_parent_name(instance);
    const bool top_status =
            is_top_battle_state_control(component_name, parent_name);
    const bool bottom_status =
            is_bottom_battle_status_control(component_name, parent_name);
    if (top_status) {
        restore_tmp_visual_colors(instance, state);
    }
    ensure_translated_tmp_material(instance);
    const bool mirror_reward_sync = is_mirror_dungeon_reward_control(component_name, parent_name);
    if (mirror_reward_sync) {
        return;
    }
    const bool is_battle_control_sync = top_status || bottom_status ||
            is_battle_skill_banner_control(component_name, parent_name) ||
            is_battle_winrate_control(component_name, parent_name) ||
            state.preserve_source_outline || state.use_regular_title_font;
    void *materials = read_tmp_material_collection(instance);
    if (materials != nullptr) {
        ManagedObjectPin materials_pin(materials);
        if (!materials_pin.alive()) return;
        // TMP's fontMaterials property exposes the component-local material
        // instances for every generated submesh, including later fallback or
        // atlas materials. Apply to the full list so one translated string
        // cannot end up with underlay on only the first material.
        int32_t count = 0;
        if (!read_collection_count(materials, &count)) return;
        count = std::min(count, 64);
        int32_t styled = 0;
        void *active_asset = g_tmp_get_font == nullptr ? nullptr :
                g_tmp_get_font(instance, g_tmp_get_font_info);
        const float face_dilate =
                (active_asset != nullptr && active_asset == g_tmp_regular_font_asset)
                ? limbus_text_layout::kChineseBattleSkillBannerFaceDilate
                : limbus_text_layout::kChineseFaceDilate;
        for (int32_t index = 0; index < count; ++index) {
            void *material = read_collection_item(materials, index);
            if (material == nullptr) continue;
            ManagedObjectPin material_pin(material);
            if (!material_pin.alive()) continue;
            if (is_battle_control_sync) {
                apply_material_visual_style(
                        material, state.source_material_style, face_dilate,
                        state.preserve_source_outline, bottom_status);
            } else {
                apply_chinese_material_underlay(material, false);
            }
            ++styled;
        }
        uint64_t syncs = g_tmp_material_syncs.fetch_add(
                1, std::memory_order_relaxed) + 1;
        if (syncs <= 12 || syncs % 500 == 0) {
            LT_LOGI("TMP translated text materials instance=%p count=%d styled=%d sync=%llu",
                    instance, count, styled,
                    static_cast<unsigned long long>(syncs));
        }
    }
}

void mirror_source_material_color(
        void *material, const std::string &property, const RuntimeColor &color) {
    if (material == nullptr ||
        (property != "_FaceColor" && property != "_OutlineColor" &&
         property != "_UnderlayColor")) {
        return;
    }
    std::vector<uintptr_t> owners;
    pthread_mutex_lock(&g_font_state_lock);
    auto registered = g_source_material_owners.find(material);
    if (registered != g_source_material_owners.end()) {
        owners = registered->second;
    }
    pthread_mutex_unlock(&g_font_state_lock);
    std::vector<void *> translated_instances;
    for (uintptr_t handle : owners) {
        void *instance = g_gchandle_get_target == nullptr ?
                nullptr : g_gchandle_get_target(handle);
        if (instance == nullptr) continue;
        bool updated = false;
        pthread_mutex_lock(&g_font_state_lock);
        auto saved = g_original_tmp_states.find(instance);
        if (saved != g_original_tmp_states.end()) {
            float *target = property == "_FaceColor" ?
                    saved->second.source_material_style.face_color :
                    (property == "_OutlineColor" ?
                            saved->second.source_material_style.outline_color :
                            saved->second.source_material_style.underlay_color);
            target[0] = color.r;
            target[1] = color.g;
            target[2] = color.b;
            target[3] = color.a;
            if (property == "_FaceColor") {
                saved->second.source_material_style.has_face_color = true;
            } else if (property == "_OutlineColor") {
                saved->second.source_material_style.has_outline_color = true;
            } else {
                saved->second.source_material_style.has_underlay_color = true;
            }
            updated = true;
        }
        pthread_mutex_unlock(&g_font_state_lock);
        if (updated) {
            const std::string component_name = read_unity_object_name(instance);
            const std::string parent_name = read_unity_parent_name(instance);
            if (is_battle_winrate_control(component_name, parent_name)) {
                translated_instances.push_back(instance);
            }
        }
    }
    for (void *instance : translated_instances) {
        sync_translated_tmp_materials(instance, true);
    }
    if (!translated_instances.empty()) {
        uint64_t mutations = g_battle_status_material_set_diagnostics.fetch_add(
                1, std::memory_order_relaxed) + 1;
        if (mutations <= 240) {
            LT_LOGI("Battle status material color mutation material=%s property=%s"
                    " color=(%.3f,%.3f,%.3f,%.3f) owners=%zu",
                    read_unity_object_name(material).c_str(), property.c_str(),
                    color.r, color.g, color.b, color.a,
                    translated_instances.size());
        }
    }
}

void mirror_source_material_float(
        void *material, const std::string &property, float value) {
    if (material == nullptr ||
        (property != "_FaceDilate" && property != "_OutlineWidth" &&
         property != "_OutlineSoftness" && property != "_UnderlayOffsetX" &&
         property != "_UnderlayOffsetY" && property != "_UnderlayDilate" &&
         property != "_UnderlaySoftness" && property != "_GradientScale" &&
         property != "_ScaleRatioA" && property != "_ScaleRatioB" &&
         property != "_ScaleRatioC" && property != "_TextureWidth" &&
         property != "_TextureHeight")) {
        return;
    }
    std::vector<uintptr_t> owners;
    pthread_mutex_lock(&g_font_state_lock);
    auto registered = g_source_material_owners.find(material);
    if (registered != g_source_material_owners.end()) {
        owners = registered->second;
    }
    pthread_mutex_unlock(&g_font_state_lock);
    std::vector<void *> translated_instances;
    for (uintptr_t handle : owners) {
        void *instance = g_gchandle_get_target == nullptr ?
                nullptr : g_gchandle_get_target(handle);
        if (instance == nullptr) continue;
        bool updated = false;
        pthread_mutex_lock(&g_font_state_lock);
        auto saved = g_original_tmp_states.find(instance);
        if (saved != g_original_tmp_states.end()) {
            MaterialVisualStyle &style = saved->second.source_material_style;
            if (property == "_FaceDilate") {
                style.face_dilate = value;
                style.has_face_dilate = true;
            } else if (property == "_OutlineWidth") {
                style.outline_width = value;
                style.has_outline_width = true;
            } else if (property == "_OutlineSoftness") {
                style.outline_softness = value;
                style.has_outline_softness = true;
            } else if (property == "_UnderlayOffsetX") {
                style.underlay_offset_x = value;
                style.has_underlay_offset_x = true;
            } else if (property == "_UnderlayOffsetY") {
                style.underlay_offset_y = value;
                style.has_underlay_offset_y = true;
            } else if (property == "_UnderlayDilate") {
                style.underlay_dilate = value;
                style.has_underlay_dilate = true;
            } else if (property == "_UnderlaySoftness") {
                style.underlay_softness = value;
                style.has_underlay_softness = true;
            } else if (property == "_GradientScale") {
                style.gradient_scale = value;
                style.has_gradient_scale = true;
            } else if (property == "_ScaleRatioA") {
                style.scale_ratio_a = value;
                style.has_scale_ratio_a = true;
            } else if (property == "_ScaleRatioB") {
                style.scale_ratio_b = value;
                style.has_scale_ratio_b = true;
            } else if (property == "_ScaleRatioC") {
                style.scale_ratio_c = value;
                style.has_scale_ratio_c = true;
            } else if (property == "_TextureWidth") {
                style.texture_width = value;
                style.has_texture_width = true;
            } else if (property == "_TextureHeight") {
                style.texture_height = value;
                style.has_texture_height = true;
            }
            updated = true;
        }
        pthread_mutex_unlock(&g_font_state_lock);
        if (updated) {
            const std::string component_name = read_unity_object_name(instance);
            const std::string parent_name = read_unity_parent_name(instance);
            if (is_battle_winrate_control(component_name, parent_name)) {
                translated_instances.push_back(instance);
            }
        }
    }
    for (void *instance : translated_instances) {
        sync_translated_tmp_materials(instance, true);
    }
    if (!translated_instances.empty()) {
        uint64_t mutations = g_battle_status_material_set_diagnostics.fetch_add(
                1, std::memory_order_relaxed) + 1;
        if (mutations <= 240) {
            LT_LOGI("Battle status material float mutation material=%s"
                    " property=%s value=%.4f owners=%zu",
                    read_unity_object_name(material).c_str(), property.c_str(),
                    value, translated_instances.size());
        }
    }
}

TmpComponentState capture_tmp_component_state(
        void *instance, void *font, size_t source_visible_codepoints) {
    TmpComponentState candidate;
    candidate.instance_weak_handle = create_weak_object_handle(instance);
    candidate.font = font;
    candidate.font_weak_handle = create_weak_object_handle(font);
    candidate.font_name = read_unity_object_name(font);
    candidate.shared_material = invoke_object_getter(g_tmp_get_shared_material_info, instance);
    candidate.shared_material_weak_handle =
            create_weak_object_handle(candidate.shared_material);
    candidate.source_material = invoke_object_getter(g_tmp_get_font_material_info, instance);
    if (candidate.source_material == nullptr) {
        candidate.source_material = candidate.shared_material;
    }
    candidate.source_material_weak_handle =
            create_weak_object_handle(candidate.source_material);
    candidate.source_material_style = read_material_visual_style(candidate.source_material);
    register_source_material_owner(
            candidate.source_material, candidate.instance_weak_handle);
    candidate.source_visible_codepoints = source_visible_codepoints;
    candidate.has_line_spacing = invoke_float_getter(
            g_tmp_get_line_spacing_info, instance, &candidate.line_spacing);
    candidate.has_font_size = invoke_float_getter(
            g_tmp_get_font_size_info, instance, &candidate.font_size);
    candidate.has_font_size_min = invoke_float_getter(
            g_tmp_get_font_size_min_info, instance, &candidate.font_size_min);
    candidate.has_font_size_max = invoke_float_getter(
            g_tmp_get_font_size_max_info, instance, &candidate.font_size_max);
    candidate.has_auto_sizing = invoke_bool_getter(
            g_tmp_get_auto_sizing_info, instance, &candidate.auto_sizing);
    if (candidate.has_font_size) {
        candidate.font_size = limbus_text_layout::configured_font_size(
                candidate.font_size, candidate.has_auto_sizing && candidate.auto_sizing,
                candidate.has_font_size_max, candidate.font_size_max);
    }
    candidate.has_word_wrapping = invoke_bool_getter(
            g_tmp_get_word_wrapping_info, instance, &candidate.word_wrapping);
    candidate.has_overflow_mode = invoke_int_getter(
            g_tmp_get_overflow_mode_info, instance, &candidate.overflow_mode);
    candidate.has_font_style = invoke_int_getter(
            g_tmp_get_font_style_info, instance, &candidate.font_style);
    candidate.has_text_color = invoke_color_getter(
            g_tmp_get_text_color_info, instance, candidate.text_color);
    candidate.has_text_color_gradient = invoke_vertex_gradient_getter(
            g_tmp_get_text_color_gradient_info, instance,
            candidate.text_color_gradient);
    candidate.has_enable_vertex_gradient = invoke_bool_getter(
            g_tmp_get_enable_vertex_gradient_info, instance,
            &candidate.enable_vertex_gradient);
    pthread_mutex_lock(&g_font_state_lock);
    prune_dead_tmp_component_states_locked();
    auto existing = g_original_tmp_states.find(instance);
    if (existing != g_original_tmp_states.end()) {
        release_object_handle(existing->second.instance_weak_handle);
        release_object_handle(existing->second.font_weak_handle);
        release_object_handle(existing->second.shared_material_weak_handle);
        release_object_handle(existing->second.source_material_weak_handle);
        release_object_handle(existing->second.translated_material_weak_handle);
        release_object_handle(existing->second.translated_material_font_weak_handle);
        g_original_tmp_states.erase(existing);
    }
    auto inserted = g_original_tmp_states.emplace(instance, candidate);
    TmpComponentState state = inserted.first->second;
    pthread_mutex_unlock(&g_font_state_lock);
    return state;
}

void refresh_tmp_visual_colors(void *instance) {
    if (instance == nullptr) return;
    float color[4] = {1.0f, 1.0f, 1.0f, 1.0f};
    float gradient[16] = {
            1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f,
            1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f};
    bool gradient_enabled = false;
    const bool has_color = invoke_color_getter(
            g_tmp_get_text_color_info, instance, color);
    const bool has_gradient = invoke_vertex_gradient_getter(
            g_tmp_get_text_color_gradient_info, instance, gradient);
    const bool has_gradient_enabled = invoke_bool_getter(
            g_tmp_get_enable_vertex_gradient_info, instance, &gradient_enabled);
    if (!has_color && !has_gradient && !has_gradient_enabled) return;
    pthread_mutex_lock(&g_font_state_lock);
    auto saved = g_original_tmp_states.find(instance);
    if (saved != g_original_tmp_states.end()) {
        if (has_color) {
            std::copy(color, color + 4, saved->second.text_color);
            saved->second.has_text_color = true;
        }
        if (has_gradient) {
            std::copy(gradient, gradient + 16,
                      saved->second.text_color_gradient);
            saved->second.has_text_color_gradient = true;
        }
        if (has_gradient_enabled) {
            saved->second.enable_vertex_gradient = gradient_enabled;
            saved->second.has_enable_vertex_gradient = true;
        }
    }
    pthread_mutex_unlock(&g_font_state_lock);
}

void restore_tmp_visual_colors(
        void *instance, const TmpComponentState &state) {
    if (instance == nullptr) return;
    if (state.has_text_color) {
        invoke_color_setter(
                g_tmp_set_text_color_info, instance, state.text_color);
    }
    if (state.has_text_color_gradient) {
        invoke_vertex_gradient_setter(
                g_tmp_set_text_color_gradient_info, instance,
                state.text_color_gradient);
    }
    if (state.has_enable_vertex_gradient) {
        invoke_bool_setter(
                g_tmp_set_enable_vertex_gradient_info, instance,
                state.enable_vertex_gradient);
    }
}

bool refresh_tmp_source_material_style(
        void *instance, TmpComponentState *state) {
    if (instance == nullptr || state == nullptr) return false;
    void *source_material = resolve_live_object(
            state->source_material_weak_handle, state->source_material);
    if (source_material == nullptr) return false;
    const MaterialVisualStyle current =
            read_material_visual_style(source_material);
    state->source_material_style = current;
    pthread_mutex_lock(&g_font_state_lock);
    auto saved = g_original_tmp_states.find(instance);
    if (saved != g_original_tmp_states.end()) {
        saved->second.source_material_style = current;
    }
    pthread_mutex_unlock(&g_font_state_lock);
    return true;
}

void log_battle_status_style_snapshot(
        void *instance, const TmpComponentState &state,
        const std::string &source, const std::string &result,
        const std::string &component_name, const std::string &parent_name) {
    uint64_t diagnostics =
            g_battle_status_style_diagnostics.fetch_add(
                    1, std::memory_order_relaxed) + 1;
    if (diagnostics > 160) return;
    const MaterialVisualStyle &style = state.source_material_style;
    std::string material_name = read_unity_object_name(state.source_material);
    LT_LOGI("Battle status style instance=%p name=%s parent=%s source=%s result=%s"
            " font=%s color=%d(%.3f,%.3f,%.3f,%.3f)"
            " gradient=%d/%d material=%s"
            " face=(%.3f,%.3f,%.3f,%.3f) outline=(%.3f,%.3f,%.3f,%.3f)"
            " sdf=(%.3f,%.3f,%.3f;%.3f,%.3f,%.3f,%.3f)"
            " underlay=(%.3f,%.3f,%.3f,%.3f)",
            instance, component_name.c_str(), parent_name.c_str(),
            source.c_str(), result.c_str(), state.font_name.c_str(),
            state.has_text_color ? 1 : 0,
            state.text_color[0], state.text_color[1],
            state.text_color[2], state.text_color[3],
            state.has_text_color_gradient ? 1 : 0,
            state.has_enable_vertex_gradient ?
                    (state.enable_vertex_gradient ? 1 : 0) : -1,
            material_name.c_str(),
            style.face_color[0], style.face_color[1],
            style.face_color[2], style.face_color[3],
            style.outline_color[0], style.outline_color[1],
            style.outline_color[2], style.outline_color[3],
            style.face_dilate, style.outline_width, style.outline_softness,
            style.gradient_scale, style.scale_ratio_a,
            style.scale_ratio_b, style.scale_ratio_c,
            style.underlay_color[0], style.underlay_color[1],
            style.underlay_color[2], style.underlay_color[3]);
}

bool is_mirror_dungeon_gift_or_shop_name(const std::string &component_name,
                                         const std::string &parent_name) {
    if (component_name == "tmp_egogiftName") return true;
    if (parent_name.find("DungeonShop_ProductItem") != std::string::npos &&
        (component_name == "[Text]Name" || component_name == "Name")) {
        return true;
    }
    if (parent_name.find("EgoGift") != std::string::npos &&
        (component_name == "[Text]Name" || component_name == "Name")) {
        return true;
    }
    return false;
}

bool is_battle_skill_banner_control(const std::string &component_name,
                                    const std::string &parent_name) {
    return (component_name == "SkillNameText" && parent_name == "SkillNameField") ||
           (component_name == "Name" && parent_name == "NameField");
}

bool is_loading_screen_hint_title(const std::string &display_text) {
    return display_text == "战斗小贴士";
}

bool is_battle_winrate_text_value(const std::string &display_text) {
    static const char *const values[] = {
            // Chinese runtime values.
            "绝对优势", "优势", "均势", "劣势", "绝对劣势", "支援", "不可选中",
            // Japanese source values observed in normal battles. The source
            // values must be recognised before translation as well as after it.
            "絶対有利", "非常に有利", "有利", "均等", "不利", "非常に不利",
            "絶対不利", "サポート", "選択不可", "不可選択",
            "一方攻撃", "双方攻撃", "相殺", "守備", "攻撃", "防御",
            "強化反撃", "反撃"
    };
    return std::any_of(std::begin(values), std::end(values),
                       [&](const char *value) { return display_text == value; });
}

bool is_battle_winrate_control(const std::string &component_name,
                               const std::string &parent_name,
                               const std::string &display_text) {
    if (is_top_battle_state_control(component_name, parent_name) ||
        is_bottom_battle_status_control(component_name, parent_name)) {
        return true;
    }
    return !display_text.empty() && is_battle_winrate_text_value(display_text);
}

bool is_top_battle_state_control(
        const std::string &component_name, const std::string &parent_name) {
    return component_name.find("WinState") != std::string::npos ||
           parent_name.find("WinState") != std::string::npos;
}

bool is_bottom_battle_status_control(
        const std::string &component_name, const std::string &parent_name) {
    return component_name.find("WinRate") != std::string::npos ||
           parent_name.find("WinRate") != std::string::npos;
}

bool is_general_ui_cjk_font(const std::string &raw_font_name) {
    std::string font_name = raw_font_name;
    std::transform(font_name.begin(), font_name.end(), font_name.begin(),
                   [](unsigned char value) { return static_cast<char>(std::tolower(value)); });
    return font_name.find("scdream") != std::string::npos ||
           font_name.find("higashiome") != std::string::npos ||
           font_name.find("addressable") != std::string::npos;
}

bool uses_bold_source_font(const TmpComponentState &state, const std::string &component_name,
                           const std::string &parent_name) {
    // Battle win-rate/operation badges are short and need the same medium-bold
    // visual weight as the Japanese source. The synthetic FontStyles.Bold flag
    // is still stripped; using the actual Bold face without that second
    // synthetic weight avoids both the previous thin appearance and hard strokes.
    if (state.use_battle_winrate_bold ||
        is_battle_winrate_control(component_name, parent_name)) {
        return true;
    }
    // Mirror dungeon reward controls (e.g. "探索结束奖励", "每周加成") are general
    // UI elements and must use the primary Sarasa Bold asset, even if their
    // component name happens to be "TilteText / Title".
    if (is_mirror_dungeon_reward_control(component_name, parent_name) ||
        is_mirror_dungeon_gift_or_shop_name(component_name, parent_name)) {
        return true;
    }
    // Battle floating skill banners and loading screen "战斗小贴士" use Regular
    // so dense CJK strokes stay open.
    if (state.use_regular_title_font ||
        is_battle_skill_banner_control(component_name, parent_name)) {
        return false;
    }
    // General UI fonts (SCDream, HigashiOme, AddressableFallbackFont) and shop
    // controls must all use the primary Sarasa Bold asset so they match the
    // #1 global fallback font and never split into Regular vs Bold runs.
    if (is_general_ui_cjk_font(state.font_name)) {
        return true;
    }
    if (state.has_font_style && (state.font_style & 1) != 0) return true;
    std::string font_name = state.font_name;
    std::transform(font_name.begin(), font_name.end(), font_name.begin(),
                   [](unsigned char value) { return static_cast<char>(std::tolower(value)); });
    static const char *const bold_markers[] = {
            "bold", "heavy", "semibold", "semi-bold", "demi", "black",
            "scdream", "higashiome"
    };
    for (const char *marker : bold_markers) {
        if (font_name.find(marker) != std::string::npos) return true;
    }
    return (component_name == "TilteText" || component_name == "Text_HintTitle" ||
            component_name == "[Text]Title" || component_name == "[Text]GroupName");
}

void *preferred_chinese_font(const TmpComponentState &state, const std::string &component_name,
                             const std::string &parent_name) {
    if (g_tmp_regular_font_asset != nullptr &&
        !uses_bold_source_font(state, component_name, parent_name)) {
        return g_tmp_regular_font_asset;
    }
    return g_tmp_font_asset;
}

bool find_tmp_component_state(void *instance, TmpComponentState *state) {
    pthread_mutex_lock(&g_font_state_lock);
    auto saved = g_original_tmp_states.find(instance);
    bool found = saved != g_original_tmp_states.end() &&
            resolve_live_object(saved->second.instance_weak_handle, instance) != nullptr;
    if (saved != g_original_tmp_states.end() && !found) {
        release_object_handle(saved->second.instance_weak_handle);
        release_object_handle(saved->second.font_weak_handle);
        release_object_handle(saved->second.shared_material_weak_handle);
        release_object_handle(saved->second.source_material_weak_handle);
        release_object_handle(saved->second.translated_material_weak_handle);
        release_object_handle(saved->second.translated_material_font_weak_handle);
        g_original_tmp_states.erase(saved);
    }
    if (found && state != nullptr) *state = saved->second;
    pthread_mutex_unlock(&g_font_state_lock);
    return found;
}

void set_tmp_component_font_material(void *instance, void *material) {
    if (instance == nullptr || material == nullptr) return;
    if (g_original_tmp_set_material != nullptr) {
        g_original_tmp_set_material(
                instance, material, g_tmp_set_font_material_info);
        return;
    }
    if (g_tmp_set_font_material_info != nullptr && g_runtime_invoke != nullptr) {
        void *args[] = {material};
        void *exception = nullptr;
        g_runtime_invoke(
                g_tmp_set_font_material_info, instance, args, &exception);
    } else if (g_tmp_set_shared_material_info != nullptr) {
        invoke_object_setter(g_tmp_set_shared_material_info, instance, material);
    }
}

void store_translated_tmp_material(
        void *instance, void *material, void *material_font) {
    if (instance == nullptr || material == nullptr || g_gchandle_get_target == nullptr) {
        return;
    }
    uintptr_t weak_handle = create_weak_object_handle(material);
    if (weak_handle == 0) return;
    uintptr_t font_weak_handle = create_weak_object_handle(material_font);
    pthread_mutex_lock(&g_font_state_lock);
    auto saved = g_original_tmp_states.find(instance);
    if (saved != g_original_tmp_states.end()) {
        release_object_handle(saved->second.translated_material_weak_handle);
        release_object_handle(saved->second.translated_material_font_weak_handle);
        saved->second.translated_material = material;
        saved->second.translated_material_weak_handle = weak_handle;
        saved->second.translated_material_font = material_font;
        saved->second.translated_material_font_weak_handle = font_weak_handle;
    } else if (g_gchandle_free != nullptr) {
        g_gchandle_free(weak_handle);
        if (font_weak_handle != 0) g_gchandle_free(font_weak_handle);
    }
    pthread_mutex_unlock(&g_font_state_lock);
}

void *ensure_translated_tmp_material(void *instance) {
    TmpComponentState state;
    if (instance == nullptr || !find_tmp_component_state(instance, &state) ||
        g_tmp_get_font == nullptr) {
        return nullptr;
    }
    refresh_tmp_source_material_style(instance, &state);
    const std::string component_name = read_unity_object_name(instance);
    const std::string parent_name = read_unity_parent_name(instance);
    const bool top_status =
            is_top_battle_state_control(component_name, parent_name);
    const bool bottom_status =
            is_bottom_battle_status_control(component_name, parent_name);
    if (top_status) {
        restore_tmp_visual_colors(instance, state);
    }
    void *active_asset = g_tmp_get_font(instance, g_tmp_get_font_info);
    if (active_asset != g_tmp_font_asset && active_asset != g_tmp_regular_font_asset) {
        void *expected_font = preferred_chinese_font(state, component_name, parent_name);
        if (expected_font != nullptr && g_tmp_set_font != nullptr) {
            g_tmp_set_font(instance, expected_font, g_tmp_set_font_info);
            active_asset = expected_font;
        } else {
            return nullptr;
        }
    }
    const bool mirror_reward = is_mirror_dungeon_reward_control(component_name, parent_name);
    const bool is_battle_control = top_status || bottom_status ||
            is_battle_skill_banner_control(component_name, parent_name) ||
            is_battle_winrate_control(component_name, parent_name) ||
            state.preserve_source_outline || state.use_regular_title_font;
    if (mirror_reward || !is_battle_control) {
        return invoke_object_getter(g_tmp_font_get_material_info, active_asset);
    }
    const float face_dilate =
            active_asset == g_tmp_regular_font_asset
            ? limbus_text_layout::kChineseBattleSkillBannerFaceDilate
            : limbus_text_layout::kChineseFaceDilate;
    void *saved_material = resolve_live_object(
            state.translated_material_weak_handle, state.translated_material);
    void *saved_font = resolve_live_object(
            state.translated_material_font_weak_handle,
            state.translated_material_font);
    if (saved_material != nullptr && saved_font == active_asset) {
        ManagedObjectPin material_pin(saved_material);
        if (material_pin.alive()) {
            apply_material_visual_style(
                    saved_material, state.source_material_style, face_dilate,
                    state.preserve_source_outline, bottom_status);
            // ForceMeshUpdate/SetMaterialDirty may rebind the component back to
            // the font asset's shared material after the clone was first stored.
            // Re-assigning the cached clone is required; styling an unbound
            // material has no visible effect and explains why later offset
            // increases appeared to do nothing.
            set_tmp_component_font_material(instance, saved_material);
            bool underlay_keyword = false;
            bool has_underlay_keyword = read_material_keyword_property(
                    saved_material, "UNDERLAY_ON", &underlay_keyword);
            MaterialVisualStyle applied = read_material_visual_style(saved_material);
            uint64_t rebinds = g_tmp_underlay_rebinds.fetch_add(
                    1, std::memory_order_relaxed) + 1;
            if (rebinds <= 30) {
                LT_LOGI("TMP underlay rebound instance=%p material=%p keyword=%d/%d"
                        " offset=(%.3f,%.3f) alpha=%.3f",
                        instance, saved_material,
                        has_underlay_keyword ? (underlay_keyword ? 1 : 0) : -1,
                        applied.has_underlay_offset_x ? 1 : 0,
                        applied.underlay_offset_x, applied.underlay_offset_y,
                        applied.underlay_color[3]);
            }
            return saved_material;
        }
    }
    void *atlas_material = invoke_object_getter(
            g_tmp_font_get_material_info, active_asset);
    if (atlas_material == nullptr || g_runtime_invoke == nullptr ||
        g_unity_object_instantiate_info == nullptr) {
        return atlas_material;
    }
    void *source_material = bottom_status ? resolve_live_object(
            state.source_material_weak_handle, state.source_material) : nullptr;
    void *material_template = bottom_status && source_material != nullptr ?
            source_material : atlas_material;
    void *args[] = {material_template};
    void *exception = nullptr;
    void *material = g_runtime_invoke(
            g_unity_object_instantiate_info, nullptr, args, &exception);
    if (exception != nullptr || material == nullptr) {
        LT_LOGW("Unable to create translated TMP material instance=%p base=%p exception=%p",
                instance, material_template, exception);
        return atlas_material;
    }
    ManagedObjectPin material_pin(material);
    if (!material_pin.alive()) return atlas_material;
    if (material_template != atlas_material) {
        void *chinese_atlas =
                read_material_texture_property(atlas_material, "_MainTex");
        if (chinese_atlas != nullptr) {
            write_material_texture_property(material, "_MainTex", chinese_atlas);
        } else {
            LT_LOGW("Unable to copy Chinese SDF atlas instance=%p source=%p",
                    instance, source_material);
        }
    }
    apply_material_visual_style(
            material, state.source_material_style, face_dilate,
            state.preserve_source_outline, bottom_status);
    store_translated_tmp_material(instance, material, active_asset);
    set_tmp_component_font_material(instance, material);
    uint64_t clones = g_tmp_material_clones.fetch_add(
            1, std::memory_order_relaxed) + 1;
    if (clones <= 20 || clones % 500 == 0) {
        LT_LOGI("Translated TMP material clone=%p base=%p instance=%p"
                " outline=%.3f faceDilate=%.3f underlay=(%.3f,%.3f)"
                " face=(%.2f,%.2f,%.2f,%.2f)"
                " outlineColor=(%.2f,%.2f,%.2f,%.2f) state=%s cloneCount=%llu",
                material, material_template, instance,
                limbus_text_layout::cjk_battle_winrate_outline_width(
                        state.source_material_style.outline_width),
                limbus_text_layout::cjk_battle_winrate_face_dilate(
                        limbus_text_layout::cjk_battle_winrate_outline_width(
                                state.source_material_style.outline_width)),
                limbus_text_layout::cjk_underlay_offset(
                        state.source_material_style.underlay_offset_x),
                limbus_text_layout::cjk_underlay_offset(
                        state.source_material_style.underlay_offset_y),
                state.source_material_style.face_color[0],
                state.source_material_style.face_color[1],
                state.source_material_style.face_color[2],
                state.source_material_style.face_color[3],
                state.source_material_style.outline_color[0],
                state.source_material_style.outline_color[1],
                state.source_material_style.outline_color[2],
                state.source_material_style.outline_color[3],
                state.battle_winrate_text.empty() ? "-" :
                        state.battle_winrate_text.c_str(),
                static_cast<unsigned long long>(clones));
    }
    return material;
}

void *translated_tmp_material(void *instance, void *requested_material) {
    // 游戏会在页签选择、胜率状态切换和角色刷新时重新写入原日文字体预设材质。
    // 若传入的是新的日文源材质预设，先同步其颜色与描边参数，再绑定中文克隆材质。
    if (instance != nullptr && requested_material != nullptr) {
        TmpComponentState state;
        if (find_tmp_component_state(instance, &state)) {
            void *saved_translated = resolve_live_object(
                    state.translated_material_weak_handle, state.translated_material);
            void *bold_base = g_tmp_font_asset == nullptr ? nullptr :
                    invoke_object_getter(g_tmp_font_get_material_info, g_tmp_font_asset);
            void *regular_base = g_tmp_regular_font_asset == nullptr ? nullptr :
                    invoke_object_getter(g_tmp_font_get_material_info, g_tmp_regular_font_asset);
            const std::string component_name = read_unity_object_name(instance);
            const std::string parent_name = read_unity_parent_name(instance);
            const bool battle_status =
                    is_battle_winrate_control(component_name, parent_name,
                                              state.battle_winrate_text);
            if (battle_status) {
                MaterialVisualStyle requested_style =
                        read_material_visual_style(requested_material);
                MaterialVisualStyle translated_style =
                        read_material_visual_style(saved_translated);
                uint64_t diagnostics =
                        g_battle_status_material_set_diagnostics.fetch_add(
                                1, std::memory_order_relaxed) + 1;
                if (diagnostics <= 240) {
                    LT_LOGI("Battle status material request instance=%p name=%s"
                            " parent=%s requested=%s saved=%s base=%d"
                            " face=(%.3f,%.3f,%.3f,%.3f)"
                            " outline=(%.3f,%.3f,%.3f,%.3f)"
                            " outlineWidth=%.3f softness=%.3f"
                            " underlay=(%.3f,%.3f,%.3f,%.3f)"
                            " underlayDilate=%.3f underlaySoftness=%.3f"
                            " sourceSDF=(%.3f,%.3f,%.3f,%.3f)"
                            " cloneSDF=(%.3f,%.3f,%.3f,%.3f)",
                            instance, component_name.c_str(), parent_name.c_str(),
                            read_unity_object_name(requested_material).c_str(),
                            read_unity_object_name(saved_translated).c_str(),
                            requested_material == bold_base ||
                                    requested_material == regular_base ? 1 : 0,
                            requested_style.face_color[0],
                            requested_style.face_color[1],
                            requested_style.face_color[2],
                            requested_style.face_color[3],
                            requested_style.outline_color[0],
                            requested_style.outline_color[1],
                            requested_style.outline_color[2],
                            requested_style.outline_color[3],
                            requested_style.outline_width,
                            requested_style.outline_softness,
                            requested_style.underlay_color[0],
                            requested_style.underlay_color[1],
                            requested_style.underlay_color[2],
                            requested_style.underlay_color[3],
                            requested_style.underlay_dilate,
                            requested_style.underlay_softness,
                            requested_style.gradient_scale,
                            requested_style.scale_ratio_a,
                            requested_style.scale_ratio_b,
                            requested_style.scale_ratio_c,
                            translated_style.gradient_scale,
                            translated_style.scale_ratio_a,
                            translated_style.scale_ratio_b,
                            translated_style.scale_ratio_c);
                }
            }
            if (requested_material != saved_translated &&
                requested_material != bold_base &&
                requested_material != regular_base) {
                MaterialVisualStyle updated_style =
                        read_material_visual_style(requested_material);
                uintptr_t owner_handle = 0;
                pthread_mutex_lock(&g_font_state_lock);
                auto saved = g_original_tmp_states.find(instance);
                if (saved != g_original_tmp_states.end()) {
                    saved->second.source_material_style = updated_style;
                    uintptr_t requested_weak =
                            create_weak_object_handle(requested_material);
                    if (requested_weak != 0) {
                        release_object_handle(
                                saved->second.source_material_weak_handle);
                        saved->second.source_material = requested_material;
                        saved->second.source_material_weak_handle =
                                requested_weak;
                        owner_handle = saved->second.instance_weak_handle;
                    }
                }
                pthread_mutex_unlock(&g_font_state_lock);
                if (owner_handle != 0) {
                    register_source_material_owner(
                            requested_material, owner_handle);
                }
            }
        }
    }
    void *font_material = ensure_translated_tmp_material(instance);
    return font_material == nullptr ? requested_material : font_material;
}

void replacement_tmp_set_shared_material(void *instance, void *material, const void *method) {
    g_original_tmp_set_shared_material(
            instance, translated_tmp_material(instance, material), method);
}

void replacement_tmp_set_material(void *instance, void *material, const void *method) {
    g_original_tmp_set_material(
            instance, translated_tmp_material(instance, material), method);
}

void replacement_material_set_color(
        void *material, void *property_name, RuntimeColor color,
        const void *method) {
    if (g_original_material_set_color != nullptr) {
        g_original_material_set_color(material, property_name, color, method);
    }
    if (has_source_material_owners(material)) {
        const std::string property = read_managed_string_value(property_name);
        mirror_source_material_color(material, property, color);
    }
}

void replacement_material_set_float(
        void *material, void *property_name, float value, const void *method) {
    if (g_original_material_set_float != nullptr) {
        g_original_material_set_float(material, property_name, value, method);
    }
    if (has_source_material_owners(material)) {
        const std::string property = read_managed_string_value(property_name);
        mirror_source_material_float(material, property, value);
    }
}

void replacement_tmp_set_text_color(
        void *instance, RuntimeColor color, const void *method) {
    if (g_original_tmp_set_color != nullptr) {
        g_original_tmp_set_color(instance, color, method);
    }
    bool updated = false;
    pthread_mutex_lock(&g_font_state_lock);
    auto saved = g_original_tmp_states.find(instance);
    if (saved != g_original_tmp_states.end()) {
        saved->second.text_color[0] = color.r;
        saved->second.text_color[1] = color.g;
        saved->second.text_color[2] = color.b;
        saved->second.text_color[3] = color.a;
        saved->second.has_text_color = true;
        updated = true;
    }
    pthread_mutex_unlock(&g_font_state_lock);
    if (updated) {
        const std::string component_name = read_unity_object_name(instance);
        const std::string parent_name = read_unity_parent_name(instance);
        if (is_battle_winrate_control(component_name, parent_name)) {
            uint64_t mutations =
                    g_battle_status_material_set_diagnostics.fetch_add(
                            1, std::memory_order_relaxed) + 1;
            if (mutations <= 240) {
                LT_LOGI("Battle status text color mutation instance=%p name=%s"
                        " parent=%s color=(%.3f,%.3f,%.3f,%.3f)",
                        instance, component_name.c_str(), parent_name.c_str(),
                        color.r, color.g, color.b, color.a);
            }
        }
    }
}

void replacement_tmp_set_font(void *instance, void *font, const void *method) {
    if (g_tmp_set_font == nullptr) return;
    if (instance == nullptr || font == nullptr ||
        font == g_tmp_font_asset || font == g_tmp_regular_font_asset) {
        g_tmp_set_font(instance, font, method);
        return;
    }
    std::string requested_name = read_unity_object_name(font);
    if (g_tmp_font_asset != nullptr &&
        is_general_ui_cjk_font(requested_name)) {
        prepend_tmp_font_fallback(font, g_tmp_font_asset);
    }
    TmpComponentState state;
    if (!find_tmp_component_state(instance, &state)) {
        g_tmp_set_font(instance, font, method);
        return;
    }
    uintptr_t new_weak = create_weak_object_handle(font);
    pthread_mutex_lock(&g_font_state_lock);
    auto saved = g_original_tmp_states.find(instance);
    if (saved != g_original_tmp_states.end()) {
        release_object_handle(saved->second.font_weak_handle);
        saved->second.font = font;
        saved->second.font_weak_handle = new_weak;
        saved->second.font_name = requested_name;
        state = saved->second;
    } else if (new_weak != 0 && g_gchandle_free != nullptr) {
        g_gchandle_free(new_weak);
    }
    pthread_mutex_unlock(&g_font_state_lock);

    std::string component_name = read_unity_object_name(instance);
    std::string parent_name = read_unity_parent_name(instance);
    void *chinese_font = preferred_chinese_font(state, component_name, parent_name);
    if (chinese_font == nullptr) {
        g_tmp_set_font(instance, font, method);
        return;
    }
    if (is_mirror_dungeon_gift_or_shop_name(component_name, parent_name)) {
        prepend_tmp_font_fallback(
                font, g_tmp_font_asset != nullptr ? g_tmp_font_asset : chinese_font);
    }
    g_tmp_set_font(instance, chinese_font, method);
    ensure_translated_tmp_material(instance);
    uint64_t resets = g_tmp_font_reset_blocks.fetch_add(1, std::memory_order_relaxed) + 1;
    if (resets <= 40 || resets % 250 == 0) {
        LT_LOGI("TMP blocked source font reset count=%llu instance=%p requestedFont=%s"
                " keptKind=%s name=%s parent=%s",
                static_cast<unsigned long long>(resets), instance,
                requested_name.c_str(),
                chinese_font == g_tmp_regular_font_asset ? "regular" : "bold",
                component_name.c_str(), parent_name.c_str());
    }
}

void restore_tmp_component_state(void *instance) {
    TmpComponentState state;
    bool restore = false;
    pthread_mutex_lock(&g_font_state_lock);
    auto saved = g_original_tmp_states.find(instance);
    if (saved != g_original_tmp_states.end()) {
        state = saved->second;
        g_original_tmp_states.erase(saved);
        restore = true;
    }
    pthread_mutex_unlock(&g_font_state_lock);
    if (!restore) return;
    // 组件会被列表复用；必须恢复字体、材质与全部排版约束，避免中文缩放泄漏到日文页面。
    void *original_font = resolve_live_object(state.font_weak_handle, state.font);
    if (g_tmp_set_font != nullptr && original_font != nullptr) {
        ManagedObjectPin font_pin(original_font);
        if (font_pin.alive()) {
            g_tmp_set_font(instance, original_font, g_tmp_set_font_info);
        }
    }
    void *original_material = resolve_live_object(
            state.shared_material_weak_handle, state.shared_material);
    if (original_material != nullptr) {
        ManagedObjectPin material_pin(original_material);
        if (material_pin.alive()) {
            invoke_object_setter(
                    g_tmp_set_shared_material_info, instance, original_material);
        }
    }
    void *source_material = resolve_live_object(
            state.source_material_weak_handle, state.source_material);
    if (source_material != nullptr) {
        ManagedObjectPin source_material_pin(source_material);
        if (source_material_pin.alive()) {
            set_tmp_component_font_material(instance, source_material);
        }
    }
    if (state.has_line_spacing) {
        invoke_float_setter(g_tmp_set_line_spacing_info, instance, state.line_spacing);
    }
    if (state.has_auto_sizing) {
        invoke_bool_setter(g_tmp_set_auto_sizing_info, instance, state.auto_sizing);
    }
    if (state.has_word_wrapping) {
        invoke_bool_setter(g_tmp_set_word_wrapping_info, instance, state.word_wrapping);
    }
    if (state.has_overflow_mode) {
        invoke_int_setter(g_tmp_set_overflow_mode_info, instance, state.overflow_mode);
    }
    if (state.has_font_size_min) {
        invoke_float_setter(g_tmp_set_font_size_min_info, instance, state.font_size_min);
    }
    if (state.has_font_size_max) {
        invoke_float_setter(g_tmp_set_font_size_max_info, instance, state.font_size_max);
    }
    if (state.has_font_size) {
        invoke_float_setter(g_tmp_set_font_size_info, instance, state.font_size);
    }
    if (state.has_font_style) {
        invoke_int_setter(g_tmp_set_font_style_info, instance, state.font_style);
    }
    restore_tmp_visual_colors(instance, state);
    release_object_handle(state.font_weak_handle);
    release_object_handle(state.shared_material_weak_handle);
    release_object_handle(state.source_material_weak_handle);
    release_object_handle(state.translated_material_weak_handle);
    release_object_handle(state.translated_material_font_weak_handle);
    release_object_handle(state.instance_weak_handle);
}

UiComponentState capture_ui_component_state(void *instance) {
    UiComponentState candidate;
    candidate.instance_weak_handle = create_weak_object_handle(instance);
    candidate.font = invoke_object_getter(g_ui_get_font_info, instance);
    candidate.font_weak_handle = create_weak_object_handle(candidate.font);
    candidate.has_line_spacing = invoke_float_getter(
            g_ui_get_line_spacing_info, instance, &candidate.line_spacing);
    candidate.has_font_size = invoke_int_getter(
            g_ui_get_font_size_info, instance, &candidate.font_size);
    candidate.has_resize_min_size = invoke_int_getter(
            g_ui_get_resize_min_size_info, instance, &candidate.resize_min_size);
    candidate.has_resize_max_size = invoke_int_getter(
            g_ui_get_resize_max_size_info, instance, &candidate.resize_max_size);
    candidate.has_resize_best_fit = invoke_bool_getter(
            g_ui_get_resize_best_fit_info, instance, &candidate.resize_best_fit);
    candidate.has_horizontal_overflow = invoke_int_getter(
            g_ui_get_horizontal_overflow_info, instance, &candidate.horizontal_overflow);
    candidate.has_vertical_overflow = invoke_int_getter(
            g_ui_get_vertical_overflow_info, instance, &candidate.vertical_overflow);
    pthread_mutex_lock(&g_font_state_lock);
    prune_dead_ui_component_states_locked();
    auto existing = g_original_ui_states.find(instance);
    if (existing != g_original_ui_states.end()) {
        release_object_handle(existing->second.instance_weak_handle);
        release_object_handle(existing->second.font_weak_handle);
        g_original_ui_states.erase(existing);
    }
    auto inserted = g_original_ui_states.emplace(instance, candidate);
    UiComponentState state = inserted.first->second;
    pthread_mutex_unlock(&g_font_state_lock);
    return state;
}

bool find_ui_component_state(void *instance, UiComponentState *state) {
    pthread_mutex_lock(&g_font_state_lock);
    auto saved = g_original_ui_states.find(instance);
    bool found = saved != g_original_ui_states.end() &&
            resolve_live_object(saved->second.instance_weak_handle, instance) != nullptr;
    if (saved != g_original_ui_states.end() && !found) {
        release_object_handle(saved->second.instance_weak_handle);
        release_object_handle(saved->second.font_weak_handle);
        g_original_ui_states.erase(saved);
    }
    if (found && state != nullptr) *state = saved->second;
    pthread_mutex_unlock(&g_font_state_lock);
    return found;
}

void restore_ui_component_state(void *instance) {
    UiComponentState state;
    bool restore = false;
    pthread_mutex_lock(&g_font_state_lock);
    auto saved = g_original_ui_states.find(instance);
    if (saved != g_original_ui_states.end()) {
        state = saved->second;
        g_original_ui_states.erase(saved);
        restore = true;
    }
    pthread_mutex_unlock(&g_font_state_lock);
    if (!restore) return;
    // 列表和页签会复用旧 UI.Text；离开中文文本时恢复原设置，避免影响未汉化资源。
    void *original_font = resolve_live_object(state.font_weak_handle, state.font);
    if (original_font != nullptr) {
        ManagedObjectPin font_pin(original_font);
        if (font_pin.alive()) {
            invoke_object_setter(g_ui_set_font_info, instance, original_font);
        }
    }
    if (state.has_line_spacing) {
        invoke_float_setter(g_ui_set_line_spacing_info, instance, state.line_spacing);
    }
    if (state.has_resize_best_fit) {
        invoke_bool_setter(g_ui_set_resize_best_fit_info, instance, state.resize_best_fit);
    }
    if (state.has_resize_min_size) {
        invoke_int_setter(g_ui_set_resize_min_size_info, instance, state.resize_min_size);
    }
    if (state.has_resize_max_size) {
        invoke_int_setter(g_ui_set_resize_max_size_info, instance, state.resize_max_size);
    }
    if (state.has_horizontal_overflow) {
        invoke_int_setter(
                g_ui_set_horizontal_overflow_info, instance, state.horizontal_overflow);
    }
    if (state.has_vertical_overflow) {
        invoke_int_setter(g_ui_set_vertical_overflow_info, instance, state.vertical_overflow);
    }
    if (state.has_font_size) {
        invoke_int_setter(g_ui_set_font_size_info, instance, state.font_size);
    }
    release_object_handle(state.font_weak_handle);
    release_object_handle(state.instance_weak_handle);
}

size_t count_visible_codepoints(const std::string &text) {
    size_t count = 0;
    for (size_t offset = 0; offset < text.size();) {
        // 富文本标签不占显示宽度，不能让颜色、样式参数把短标签误判成长正文。
        if (text[offset] == '<') {
            size_t tag_end = text.find('>', offset + 1);
            if (tag_end != std::string::npos) {
                offset = tag_end + 1;
                continue;
            }
        }
        unsigned char byte = static_cast<unsigned char>(text[offset++]);
        if ((byte & 0xc0) != 0x80) ++count;
    }
    return count;
}

bool apply_translated_tmp_style(void *instance, void *managed_text,
                                const TmpComponentState &state) {
    if (managed_text == nullptr || g_string_length == nullptr ||
        g_string_chars == nullptr) {
        return false;
    }
    int32_t length = g_string_length(managed_text);
    std::string text;
    if (length < 0 || length > 1024 * 1024 ||
        !utf16_to_utf8(g_string_chars(managed_text), length, &text)) {
        return false;
    }

    if (!state.has_font_size || state.font_size <= 0.0f) return false;

    size_t visible_length = count_visible_codepoints(text);
    ComponentRect rect = read_component_rect(instance, g_tmp_get_rect_transform_info);
    std::string component_name = read_unity_object_name(instance);
    std::string parent_name = read_unity_parent_name(instance);
    const bool mirror_reward =
            is_mirror_dungeon_reward_control(component_name, parent_name, text);
    bool battle_banner_title = !mirror_reward &&
            is_battle_skill_banner_control(component_name, parent_name) &&
            (!rect.valid || rect.height <= 100.0f);
    bool hint_title = !mirror_reward && rect.valid && rect.width >= 400.0f && rect.height <= 100.0f &&
            (component_name == "Text_HintTitle");
    bool loading_hint_title = is_loading_screen_hint_title(text);
    bool battle_winrate_label = is_battle_winrate_control(component_name, parent_name, text);
    bool skill_title = battle_banner_title || hint_title;
    bool use_regular_style = skill_title || loading_hint_title;
    bool strip_synthetic_bold = use_regular_style || battle_winrate_label;
    int32_t applied_font_style = state.font_style;
    if (strip_synthetic_bold && state.has_font_style && (state.font_style & 1) != 0) {
        applied_font_style = state.font_style & ~1;
        invoke_int_setter(g_tmp_set_font_style_info, instance, applied_font_style);
    }
    void *battle_state_font = use_regular_style ? g_tmp_regular_font_asset :
            (battle_winrate_label ? g_tmp_font_asset : nullptr);
    if (battle_state_font != nullptr && g_tmp_set_font != nullptr) {
        g_tmp_set_font(instance, battle_state_font, g_tmp_set_font_info);
        ensure_translated_tmp_material(instance);
    }
    if (!is_bottom_battle_status_control(component_name, parent_name)) {
        restore_tmp_visual_colors(instance, state);
    }
    if (skill_title) {
        uint64_t title_logs = g_tmp_title_style_logs.fetch_add(
                1, std::memory_order_relaxed) + 1;
        if (title_logs <= 30) {
            LT_LOGI("TMP translated title instance=%p name=%s parent=%s style=%d->%d"
                    " size=%.1f rect=%.1fx%.1f chars=%zu regular=%d",
                    instance, component_name.c_str(), parent_name.c_str(),
                    state.font_style, applied_font_style, state.font_size,
                    rect.width, rect.height, visible_length, skill_title);
        }
    }

    uint64_t decisions = g_layout_decisions.fetch_add(1, std::memory_order_relaxed) + 1;
    if (decisions <= 20 || decisions % 500 == 0) {
        // 样式审计只记录控件身份和原始排版参数，不输出玩家文本内容。
        LT_LOGI("TMP preserved layout decision=%llu chars=%zu rect=%.1fx%.1f base=%.1f"
                " max=%.1f min=%.1f auto=%d wrap=%d breaks=%d",
                static_cast<unsigned long long>(decisions), visible_length,
                rect.width, rect.height, state.font_size, state.font_size_max,
                state.font_size_min, state.auto_sizing, state.word_wrapping,
                limbus_text_layout::has_explicit_break(text));
    }
    const bool egogift_or_shop_name =
            is_mirror_dungeon_gift_or_shop_name(component_name, parent_name);
    const bool explicit_break = limbus_text_layout::has_explicit_break(text);
    const bool single_line = skill_title || egogift_or_shop_name ||
            limbus_text_layout::is_single_line(
                    explicit_break, rect.valid, rect.height, state.font_size,
                    state.has_word_wrapping, state.word_wrapping);
    const bool overflow_could_clip = state.has_overflow_mode &&
            (state.overflow_mode == 1 || state.overflow_mode == 2 ||
             state.overflow_mode == 3 || state.overflow_mode == 5);
    const bool single_line_needs_fit =
            single_line && !explicit_break &&
            (skill_title || egogift_or_shop_name || overflow_could_clip ||
             (rect.valid && rect.width > 0.0f && state.font_size > 0.0f &&
              static_cast<float>(visible_length) * state.font_size * 0.80f > rect.width));
    const bool translated_is_longer = state.source_visible_codepoints > 0 &&
            visible_length > state.source_visible_codepoints;
    if (single_line_needs_fit) {
        // Prevent single-line skill/gift/UI titles from ever being truncated
        // with "...": disable ellipsis/truncation, keep them on a single line,
        // and let TMP auto-size down only when the title exceeds the banner width.
        float maximum = state.font_size;
        if (state.has_font_size_max && state.font_size_max > 0.0f) {
            maximum = std::max(maximum, state.font_size_max);
        }
        float minimum = std::max(10.0f, maximum * 0.45f);
        invoke_bool_setter(g_tmp_set_word_wrapping_info, instance, false);
        invoke_float_setter(g_tmp_set_font_size_min_info, instance, minimum);
        invoke_float_setter(g_tmp_set_font_size_max_info, instance, maximum);
        invoke_bool_setter(g_tmp_set_auto_sizing_info, instance, true);
        invoke_int_setter(g_tmp_set_overflow_mode_info, instance, 0);
        force_tmp_mesh_update(instance);
        uint64_t fits = g_long_text_fit_actions.fetch_add(
                1, std::memory_order_relaxed) + 1;
        if (fits <= 20 || fits % 200 == 0) {
            LT_LOGI("TMP single-line title fit instance=%p name=%s chars=%zu->%zu"
                    " size=%.1f..%.1f rect=%.1fx%.1f sourceOverflow=%d",
                    instance, component_name.c_str(), state.source_visible_codepoints,
                    visible_length, minimum, maximum, rect.width, rect.height,
                    state.overflow_mode);
        }
    } else if (overflow_could_clip && translated_is_longer && !explicit_break) {
        float maximum = state.font_size;
        float minimum = std::max(8.0f, maximum * 0.72f);
        if (state.has_font_size_max && state.font_size_max > 0.0f) {
            maximum = state.font_size_max;
        }
        if (state.has_font_size_min && state.font_size_min > 0.0f &&
            state.font_size_min <= maximum) {
            minimum = std::max(minimum, state.font_size_min);
        }
        invoke_bool_setter(g_tmp_set_word_wrapping_info, instance, true);
        invoke_float_setter(g_tmp_set_font_size_min_info, instance, minimum);
        invoke_float_setter(g_tmp_set_font_size_max_info, instance, maximum);
        invoke_bool_setter(g_tmp_set_auto_sizing_info, instance, true);
        invoke_int_setter(g_tmp_set_overflow_mode_info, instance, 0);
        force_tmp_mesh_update(instance);
        uint64_t fits = g_long_text_fit_actions.fetch_add(
                1, std::memory_order_relaxed) + 1;
        if (fits <= 20 || fits % 200 == 0) {
            LT_LOGI("TMP long text fit instance=%p chars=%zu->%zu size=%.1f..%.1f"
                    " sourceOverflow=%d", instance, state.source_visible_codepoints,
                    visible_length, minimum, maximum, state.overflow_mode);
        }
    }
    return skill_title;
}

void *apply_tmp_component_layout_markup(void *instance, void *managed_text) {
    if (instance == nullptr || managed_text == nullptr ||
        g_string_length == nullptr || g_string_chars == nullptr) {
        return managed_text;
    }
    int32_t length = g_string_length(managed_text);
    std::string text;
    if (length < 0 || length > 4096 ||
        !utf16_to_utf8(g_string_chars(managed_text), length, &text) ||
        text.find("<line-height=") != std::string::npos) {
        return managed_text;
    }
    std::string component_name = read_unity_object_name(instance);
    if (component_name != "[Text]GroupName") {
        return managed_text;
    }
    // 某些页面会把包内换行压成空格或直接去掉。通过当前汉化包自动生成的兼容索引
    // 恢复原始分行，确保任意人格标题都遵循包内排版，而不是只修复已知个例。
    auto package_layout = g_package_line_breaks.find(text);
    if (package_layout != g_package_line_breaks.end()) {
        text = package_layout->second;
    }
    if (text.find('\n') == std::string::npos) {
        return managed_text;
    }
    // 人格名称控件会忽略运行时 lineSpacing，却会在生成网格时遵守 TMP 富文本行高。
    // 只移动空格处的换行，不增删或改写汉化包正文，并统一使用用户指定的 150% 行高。
    size_t line_count = 0;
    std::string reflowed = reflow_personality_title(text, &line_count);
    // 用户要求所有人格卡底栏保持一致，二行与三行标题统一采用 150% 行高。
    std::string formatted = apply_uniform_line_height(reflowed, 150);
    void *replacement = new_managed_string(formatted);
    if (replacement == nullptr) return managed_text;
    if (!remember_translation_owned_string(replacement)) return managed_text;
    pthread_mutex_lock(&g_font_state_lock);
    g_translated_managed_texts.insert(formatted);
    pthread_mutex_unlock(&g_font_state_lock);
    return replacement;
}

bool is_ascii_question_placeholder(const std::string &text) {
    bool has_question = false;
    for (char value : text) {
        if (value == '?') {
            has_question = true;
            continue;
        }
        if (value == ' ' || value == '\t' || value == '\r' || value == '\n') continue;
        return false;
    }
    return has_question;
}

void *prepare_tmp_text(void *instance, void *managed_text) {
    void *replacement = managed_text;
    if (instance != nullptr && managed_text != nullptr && g_string_length != nullptr &&
        g_string_chars != nullptr && g_string_new_utf16 != nullptr) {
        refresh_tmp_visual_colors(instance);
        int32_t length = g_string_length(managed_text);
        std::string source;
        if (length >= 0 && length <= 1024 * 1024 &&
            utf16_to_utf8(g_string_chars(managed_text), length, &source)) {
            // A recycled list row can receive a new price or label while still
            // carrying the Chinese font style from its previous item. Restore the
            // pristine component before taking a new snapshot; otherwise the old
            // row's title size/material leaks into the new numeric value.
            if (!is_registered_translation_text(source) &&
                g_tmp_get_font != nullptr && g_tmp_get_font_info != nullptr) {
                void *font_before_translation =
                        g_tmp_get_font(instance, g_tmp_get_font_info);
                if (font_before_translation == g_tmp_font_asset ||
                    font_before_translation == g_tmp_regular_font_asset) {
                    restore_tmp_component_state(instance);
                }
            }
            // TextData formatter hooks can translate before the value reaches
            // TMP_Text.  In that case the setter sees an already-Chinese string
            // and cannot use pointer inequality to know that a fallback font is
            // required.  Prepare the fallback before any non-ASCII text is laid
            // out; it remains fallback-only, so Japanese/Latin primary styling
            // is unchanged.
            bool needs_fallback = std::any_of(source.begin(), source.end(),
                    [](unsigned char byte) { return (byte & 0x80) != 0; });
            if (needs_fallback) ensure_tmp_font();
            replacement = translate_managed_string(managed_text);
            // Shop costs use ASCII question marks as a deliberate game-font
            // placeholder. Replacing them with full-width Chinese question marks
            // changes their advance widths and leaves visible gaps around the cost.
            if (is_ascii_question_placeholder(source) && replacement != managed_text) {
                replacement = managed_text;
            }
            if (source == "翅膀") {
                std::string component_name = read_unity_object_name(instance);
                std::string parent_name = read_unity_parent_name(instance);
                std::vector<std::string> ancestors = read_unity_ancestor_names(instance, 8);
                bool dante_note_context =
                        (component_name == "[Text]SmallCategory" &&
                         parent_name == "[Rect]CategoryText") ||
                        (component_name == "[Text]Title" &&
                         parent_name == "[Rect]CurrentCategory") ||
                        std::any_of(ancestors.begin(), ancestors.end(),
                                [](const std::string &name) {
                                    return name.find("DanteNote") != std::string::npos ||
                                           name.find("StoryTheater") != std::string::npos;
                                });
                if (dante_note_context) {
                    void *corrected = new_managed_string("翼");
                    if (corrected != nullptr && remember_translation_owned_string(corrected)) {
                        replacement = corrected;
                        pthread_mutex_lock(&g_font_state_lock);
                        g_translated_managed_texts.insert("翼");
                        pthread_mutex_unlock(&g_font_state_lock);
                        std::string ancestry;
                        for (const std::string &name : ancestors) {
                            if (!ancestry.empty()) ancestry += " > ";
                            ancestry += name;
                        }
                        LT_LOGI("DanteNote Wing term corrected instance=%p name=%s parent=%s"
                                " ancestors=%s",
                                instance, component_name.c_str(), parent_name.c_str(),
                                ancestry.c_str());
                    }
                }
            }
            if (source == "助战") {
                std::string component_name = read_unity_object_name(instance);
                std::string parent_name = read_unity_parent_name(instance);
                if (component_name == "[Tmpro]WinRateText" &&
                    parent_name == "[Image]WinRateTextBg") {
                    void *corrected = new_managed_string("支援");
                    if (corrected != nullptr && remember_translation_owned_string(corrected)) {
                        replacement = corrected;
                        pthread_mutex_lock(&g_font_state_lock);
                        g_translated_managed_texts.insert("支援");
                        pthread_mutex_unlock(&g_font_state_lock);
                    }
                }
            }
            if (replacement != managed_text && !ensure_tmp_font()) {
                return managed_text;
            }
            replacement = apply_tmp_component_layout_markup(instance, replacement);
            std::string display_text;
            int32_t display_length = g_string_length(replacement);
            if (display_length >= 0 && display_length <= 1024 * 1024) {
                utf16_to_utf8(g_string_chars(replacement), display_length, &display_text);
            }
            std::string restored_full_text;
            if (!display_text.empty() &&
                restore_ellipsis_truncated_translation(display_text, &restored_full_text)) {
                void *unabbreviated = new_managed_string(restored_full_text);
                if (unabbreviated != nullptr &&
                    remember_translation_owned_string(unabbreviated)) {
                    replacement = unabbreviated;
                    display_text = restored_full_text;
                }
            }
            bool translated = is_translated_managed_string(replacement);
            void *current_font = g_tmp_get_font == nullptr ? nullptr :
                    g_tmp_get_font(instance, g_tmp_get_font_info);
            bool already_chinese = current_font == g_tmp_font_asset ||
                    current_font == g_tmp_regular_font_asset;
            std::string component_name = read_unity_object_name(instance);
            std::string parent_name = read_unity_parent_name(instance);
            const bool egogift_name =
                    is_mirror_dungeon_gift_or_shop_name(component_name, parent_name);
            const bool mirror_reward_ui =
                    is_mirror_dungeon_reward_control(component_name, parent_name, display_text) ||
                    is_mirror_dungeon_reward_control(component_name, parent_name, source);
            const bool battle_skill_banner =
                    is_battle_skill_banner_control(component_name, parent_name);
            const bool loading_hint_title =
                    is_loading_screen_hint_title(display_text);
            const bool battle_winrate_label =
                    is_battle_winrate_control(component_name, parent_name, display_text);
            const bool regular_styled_control =
                    battle_skill_banner || loading_hint_title;
            const bool strip_synthetic_bold =
                    regular_styled_control || battle_winrate_label;
            std::string current_font_name = current_font == nullptr ? std::string() :
                    read_unity_object_name(current_font);
            const bool general_ui_cjk = is_general_ui_cjk_font(current_font_name);
            const bool may_be_composed_cjk_ui =
                    egogift_name || mirror_reward_ui || battle_skill_banner ||
                    battle_winrate_label || general_ui_cjk;
            bool promoted_egogift = false;
            if (!translated && !already_chinese && current_font != nullptr &&
                may_be_composed_cjk_ui) {
                ManagedObjectPin font_pin(current_font);
                if (font_pin.alive() &&
                    text_requires_chinese_primary_font(current_font, display_text)) {
                    // Some formatter/truncation paths compose the final Chinese
                    // display string after individual fields were translated.
                    // Promote the control so shared and simplified characters
                    // use the same primary Chinese font.
                    translated = true;
                    promoted_egogift = true;
                    if (!display_text.empty()) {
                        pthread_mutex_lock(&g_font_state_lock);
                        g_translated_managed_texts.insert(display_text);
                        pthread_mutex_unlock(&g_font_state_lock);
                    }
                    request_tmp_rebuild(instance);
                    uint64_t promotions = g_fallback_primary_promotions.fetch_add(
                            1, std::memory_order_relaxed) + 1;
                    if (promotions <= 40 || promotions % 250 == 0) {
                        LT_LOGI("TMP mixed fallback promoted instance=%p name=%s parent=%s"
                                " currentFont=%s chars=%zu reason=missing-cjk-glyph",
                                instance, component_name.c_str(), parent_name.c_str(),
                                current_font_name.c_str(),
                                count_visible_codepoints(display_text));
                    }
                }
            }
            const char *probe_kind = known_text_probe_kind(source);
            const char *tracked_kind =
                    is_tracked_mirror_result_control(
                            probe_kind, component_name, parent_name) ?
                    probe_kind : nullptr;
            if (probe_kind != nullptr) {
                LT_LOGI("Known text renderer kind=%s renderer=TMP instance=%p"
                        " name=%s parent=%s translated=%d source=%s result=%s",
                        probe_kind, instance, component_name.c_str(),
                        parent_name.c_str(), translated ? 1 : 0,
                        source.c_str(), display_text.c_str());
                log_known_text_probe(
                        probe_kind, "tmp-renderer", source, display_text,
                        translated, false, 0);
            }
            log_short_text_diagnostic(
                    "TMP", instance, managed_text, replacement, translated);
            if (translated && ensure_tmp_font() && g_tmp_get_font != nullptr &&
                g_tmp_set_font != nullptr) {
                if (!already_chinese) {
                    TmpComponentState source_state =
                            capture_tmp_component_state(
                                    instance, current_font,
                                    count_visible_codepoints(source));
                    source_state.known_text_kind =
                            tracked_kind == nullptr ? std::string() : std::string(tracked_kind);
                    source_state.use_regular_title_font = regular_styled_control;
                    source_state.use_battle_winrate_bold = battle_winrate_label;
                    source_state.preserve_source_outline = battle_winrate_label;
                    source_state.battle_winrate_text =
                            battle_winrate_label ? display_text : std::string();
                    pthread_mutex_lock(&g_font_state_lock);
                    auto saved_state = g_original_tmp_states.find(instance);
                    if (saved_state != g_original_tmp_states.end()) {
                        saved_state->second.use_regular_title_font = regular_styled_control;
                        saved_state->second.use_battle_winrate_bold = battle_winrate_label;
                        saved_state->second.preserve_source_outline = battle_winrate_label;
                        saved_state->second.known_text_kind =
                                tracked_kind == nullptr ? std::string() : std::string(tracked_kind);
                        saved_state->second.battle_winrate_text =
                                source_state.battle_winrate_text;
                        saved_state->second.source_material_style =
                                source_state.source_material_style;
                    }
                    pthread_mutex_unlock(&g_font_state_lock);
                    void *chinese_font = preferred_chinese_font(
                            source_state, component_name, parent_name);
                    const bool ui_or_banner =
                            egogift_name || mirror_reward_ui || regular_styled_control ||
                            battle_winrate_label || is_general_ui_cjk_font(source_state.font_name);
                    if (current_font != nullptr && ui_or_banner) {
                        prepend_tmp_font_fallback(
                                current_font,
                                g_tmp_font_asset != nullptr ? g_tmp_font_asset : chinese_font);
                    }
                    if (ui_or_banner) {
                        preload_cjk_glyphs(chinese_font, display_text);
                    }
                    if (strip_synthetic_bold && source_state.has_font_style &&
                        (source_state.font_style & 1) != 0) {
                        invoke_int_setter(g_tmp_set_font_style_info, instance,
                                          source_state.font_style & ~1);
                    }
                    g_tmp_set_font(instance, chinese_font, g_tmp_set_font_info);
                    // Rebuild UI/popup/shop and battle skill banner controls with
                    // ignoreActiveState=true so every character is resolved from
                    // the target Chinese font before the panel becomes visible.
                    if (ui_or_banner || promoted_egogift) {
                        request_tmp_rebuild(instance);
                    }
                    // Every component receives its own Chinese material clone. The
                    // clone keeps the source control's face colour, outline and
                    // underlay instead of mutating the shared Chinese font asset.
                    ensure_translated_tmp_material(instance);
                    if (battle_winrate_label) {
                        log_battle_status_style_snapshot(
                                instance, source_state, source, display_text,
                                component_name, parent_name);
                    }
                    uint64_t switches = g_tmp_primary_font_switches.fetch_add(
                            1, std::memory_order_relaxed) + 1;
                    if (switches == 1 || switches % 500 == 0) {
                        // 周期日志会保留到较晚导出的诊断包，便于确认主字体切换确实发生。
                        LT_LOGI("TMP Chinese primary font switches=%llu font=%p kind=%s"
                                " sourceFont=%s name=%s parent=%s",
                                static_cast<unsigned long long>(switches), chinese_font,
                                chinese_font == g_tmp_regular_font_asset ? "regular" : "bold",
                                source_state.font_name.c_str(), component_name.c_str(),
                                parent_name.c_str());
                    }
                    if (egogift_name) {
                        uint64_t diagnostics = g_egogift_name_diagnostics.fetch_add(
                                1, std::memory_order_relaxed) + 1;
                        if (diagnostics <= 40) {
                            LT_LOGI("TMP gift name style instance=%p fontKind=%s"
                                    " sourceFont=%s sourceStyle=%d chars=%zu"
                                    " underlaySource=(%.3f,%.3f) name=%s parent=%s",
                                    instance,
                                    chinese_font == g_tmp_regular_font_asset ?
                                            "regular" : "bold",
                                    source_state.font_name.c_str(),
                                    source_state.font_style,
                                    count_visible_codepoints(source),
                                    source_state.source_material_style.underlay_offset_x,
                                    source_state.source_material_style.underlay_offset_y,
                                    component_name.c_str(), parent_name.c_str());
                        }
                    }
                } else {
                    pthread_mutex_lock(&g_font_state_lock);
                    auto saved_state = g_original_tmp_states.find(instance);
                    if (saved_state != g_original_tmp_states.end()) {
                        saved_state->second.use_regular_title_font = regular_styled_control;
                        saved_state->second.use_battle_winrate_bold = battle_winrate_label;
                        saved_state->second.preserve_source_outline = battle_winrate_label;
                        saved_state->second.known_text_kind =
                                tracked_kind == nullptr ? std::string() : std::string(tracked_kind);
                        saved_state->second.battle_winrate_text =
                                battle_winrate_label ? display_text : std::string();
                    }
                    pthread_mutex_unlock(&g_font_state_lock);
                    TmpComponentState existing_state;
                    if (find_tmp_component_state(instance, &existing_state)) {
                        if (battle_winrate_label) {
                            log_battle_status_style_snapshot(
                                    instance, existing_state, source, display_text,
                                    component_name, parent_name);
                        }
                        void *expected_font = preferred_chinese_font(
                                existing_state, component_name, parent_name);
                        if (expected_font != nullptr && current_font != expected_font) {
                            g_tmp_set_font(instance, expected_font, g_tmp_set_font_info);
                            current_font = expected_font;
                        }
                        if (!strip_synthetic_bold && existing_state.has_font_style) {
                            invoke_int_setter(g_tmp_set_font_style_info, instance,
                                              existing_state.font_style);
                        }
                    }
                    if (strip_synthetic_bold) {
                        int32_t current_style = 0;
                        if (invoke_int_getter(g_tmp_get_font_style_info, instance, &current_style) &&
                            (current_style & 1) != 0) {
                            invoke_int_setter(g_tmp_set_font_style_info, instance,
                                              current_style & ~1);
                        }
                        preload_cjk_glyphs(current_font, display_text);
                        request_tmp_rebuild(instance);
                    }
                    if (egogift_name || mirror_reward_ui) {
                        preload_cjk_glyphs(current_font, display_text);
                        request_tmp_rebuild(instance);
                        uint64_t diagnostics = g_egogift_name_diagnostics.fetch_add(
                                1, std::memory_order_relaxed) + 1;
                        if (diagnostics <= 40) {
                            LT_LOGI("TMP gift/reward already Chinese instance=%p fontKind=%s"
                                    " chars=%zu name=%s parent=%s",
                                    instance,
                                    current_font == g_tmp_regular_font_asset ?
                                            "regular" : "bold",
                                    count_visible_codepoints(source),
                                    component_name.c_str(), parent_name.c_str());
                        }
                    }
                    ensure_translated_tmp_material(instance);
                }
            } else if (!translated) {
                restore_tmp_component_state(instance);
            }
            if (replacement != managed_text) {
                uint64_t hits = __atomic_add_fetch(&g_translation_hits, 1, __ATOMIC_RELAXED);
                if (hits == 1 || hits % 500 == 0) {
                    LT_LOGI("TMP translation hits=%llu", static_cast<unsigned long long>(hits));
                }
            }
        }
    }
    return replacement;
}

bool tmp_active_in_hierarchy(void *instance) {
    if (instance == nullptr || g_component_get_game_object_info == nullptr ||
        g_game_object_get_active_in_hierarchy_info == nullptr) {
        return false;
    }
    void *game_object = invoke_object_getter(g_component_get_game_object_info, instance);
    bool active = false;
    return invoke_bool_getter(
            g_game_object_get_active_in_hierarchy_info, game_object, &active) && active;
}

int32_t tmp_mesh_vertex_count(void *instance) {
    if (instance == nullptr || g_tmp_get_mesh_info == nullptr) return -1;
    void *mesh = invoke_object_getter(g_tmp_get_mesh_info, instance);
    if (mesh == nullptr) return -1;
    int32_t vertex_count = -1;
    invoke_int_getter(
            find_inherited_runtime_method(mesh, "get_vertexCount", 0),
            mesh, &vertex_count);
    return vertex_count;
}

void log_known_tmp_render_state(
        const std::string &kind, const char *stage, void *instance);

bool resubmit_known_tmp_text_if_empty(void *instance, const char *stage) {
    if (instance == nullptr || !tmp_active_in_hierarchy(instance) ||
        g_tmp_get_text_value_info == nullptr ||
        g_original_tmp_set_text_one == nullptr ||
        g_tmp_set_text_one_info == nullptr) {
        return false;
    }
    void *current_text = invoke_object_getter(g_tmp_get_text_value_info, instance);
    if (current_text == nullptr) return false;
    std::string text_str = read_managed_string_value(current_text);
    if (text_str.empty()) return false;

    int32_t current_vertices = tmp_mesh_vertex_count(instance);
    size_t expected_codepoints = count_visible_codepoints(text_str);
    int32_t expected_min_vertices = static_cast<int32_t>(expected_codepoints) * 4;

    // 仅当网格顶点确实缺失（例如 Inactive 状态下初次排版因字形未预热被跳过）时才需要补充排版；
    // 顶点完整时绝不重复清空，避免打断 UGUI Canvas 首帧排版与破坏预制体 Mask/Stencil 材质链。
    const bool vertices_missing = current_vertices <= 0 ||
            (expected_min_vertices > 0 && current_vertices < expected_min_vertices);

    if (!vertices_missing) {
        return false;
    }

    void *font = g_tmp_get_font == nullptr ? nullptr :
            g_tmp_get_font(instance, g_tmp_get_font_info);
    if (font != nullptr) {
        preload_cjk_glyphs(font, text_str);
    }
    if (g_tmp_font_asset != nullptr && font != g_tmp_font_asset) {
        preload_cjk_glyphs(g_tmp_font_asset, text_str);
    }

    // 预载缺失 CJK 字形后，直接将原文本重新提交给 TMP 原生 setter，强制排版引擎重构几何网格
    if (g_original_tmp_set_text_one != nullptr && g_tmp_set_text_one_info != nullptr) {
        g_original_tmp_set_text_one(instance, current_text, g_tmp_set_text_one_info);
    }
    force_tmp_mesh_update(instance);
    LT_LOGI("Known TMP text resubmitted stage=%s instance=%p name=%s parent=%s"
            " verticesBefore=%d verticesAfter=%d expectedMin=%d text=%s",
            stage == nullptr ? "-" : stage, instance,
            read_unity_object_name(instance).c_str(),
            read_unity_parent_name(instance).c_str(),
            current_vertices, tmp_mesh_vertex_count(instance),
            expected_min_vertices, text_str.c_str());
    return true;
}

void finalize_tmp_text(void *instance, void *managed_text) {
    if (instance == nullptr || managed_text == nullptr) {
        return;
    }
    const bool promoted_rebuild = has_pending_tmp_rebuild(instance);
    if (!is_translated_managed_string(managed_text) && !promoted_rebuild) {
        return;
    }
    TmpComponentState state;
    bool found_state = find_tmp_component_state(instance, &state);
    if (found_state) {
        // 原始 setter 完成后只处理明确需要的标题例外；其余字号、换行、溢出和
        // 行距全部沿用游戏为该控件配置的原始值。
        apply_translated_tmp_style(instance, managed_text, state);
    }
    const std::string finalized_text = read_managed_string_value(managed_text);
    const char *probe_kind = known_text_probe_kind(finalized_text);
    const bool requested_rebuild = consume_tmp_rebuild_request(instance);
    if (requested_rebuild) {
        // Setting fontAsset alone does not guarantee that TMP discards the old
        // per-character run generated with the Japanese primary font. Rebuild
        // only controls that were promoted after observing a missing CJK glyph,
        // then immediately rebind the component-local Chinese material so the
        // rebuild cannot restore the font asset's shared material.
        force_tmp_mesh_update(instance);
        if (g_tmp_set_alpha_info != nullptr && g_tmp_get_alpha_info != nullptr) {
            // Reapplying TMP's existing alpha value also dirties the mesh. Keep
            // this in addition to ForceMeshUpdate because some trimmed builds
            // expose the method but defer the actual layout update.
            float alpha = 1.0f;
            if (invoke_float_getter(g_tmp_get_alpha_info, instance, &alpha)) {
                invoke_float_setter(g_tmp_set_alpha_info, instance, alpha);
            }
        }
        ensure_translated_tmp_material(instance);
        if (probe_kind != nullptr) {
            LT_LOGI("Known TMP mesh refresh kind=%s instance=%p requested=%d"
                    " name=%s parent=%s",
                    probe_kind, instance, requested_rebuild ? 1 : 0,
                    read_unity_object_name(instance).c_str(),
                    read_unity_parent_name(instance).c_str());
        }
    }
    // The formatter can rebuild submeshes after set_text, so refresh the
    // component's current per-submesh material list after the original setter.
    // Dynamic atlases can add a new submesh material after the setter returns.
    // Reapply the full list after every translated submission so one word cannot
    // render with an outline on only the glyphs that landed in the first atlas.
    sync_translated_tmp_materials(instance, true);
    const bool tracked_mirror_result = false;
    if (g_string_length != nullptr && g_string_chars != nullptr && g_tmp_get_font != nullptr) {
        int32_t length = g_string_length(managed_text);
        std::string text;
        if (length >= 0 && length <= 4096 &&
            utf16_to_utf8(g_string_chars(managed_text), length, &text) &&
            text.find("食欲") != std::string::npos) {
            uint64_t diagnostics = g_food_title_diagnostics.fetch_add(
                    1, std::memory_order_relaxed) + 1;
            if (diagnostics <= 30) {
                void *font = g_tmp_get_font(instance, g_tmp_get_font_info);
                int32_t font_kind = font == g_tmp_regular_font_asset ? 2 :
                        (font == g_tmp_font_asset ? 1 : 0);
                float font_size = 0.0f;
                int32_t font_style = 0;
                ComponentRect rect = read_component_rect(
                        instance, g_tmp_get_rect_transform_info);
                invoke_float_getter(g_tmp_get_font_size_info, instance, &font_size);
                invoke_int_getter(g_tmp_get_font_style_info, instance, &font_style);
                void *materials = read_tmp_material_collection(instance);
                int32_t material_count = 0;
                read_collection_count(materials, &material_count);
                void *base_material = invoke_object_getter(
                        g_tmp_font_get_material_info, font);
                MaterialOutlineStyle material_style = read_material_outline(base_material);
                LT_LOGI("TMP food title diagnostic instance=%p name=%s parent=%s"
                        " fontKind=%d size=%.1f style=%d rect=%.1fx%.1f"
                        " materials=%d outline=%.4f dilate=%.4f",
                        instance, read_unity_object_name(instance).c_str(),
                        read_unity_parent_name(instance).c_str(), font_kind,
                        font_size, font_style, rect.width, rect.height,
                        material_count, material_style.width, material_style.face_dilate);
            }
        }
    }
    if (is_navigation_managed_text(managed_text)) {
        float alpha_before = -1.0f;
        invoke_float_getter(g_tmp_get_alpha_info, instance, &alpha_before);
        // 底部导航是常驻 TMP 控件，游戏在快速切页时偶尔会把共享网格的 alpha
        // 或网格刷新状态留在上一帧。标签文本已正确写入时，强制恢复可见并
        // 生成一次网格，避免个别按钮只剩图标。
        invoke_float_setter(g_tmp_set_alpha_info, instance, 1.0f);
        force_tmp_mesh_update(instance);
        uint64_t refreshes = g_navigation_refreshes.fetch_add(
                1, std::memory_order_relaxed) + 1;
        if (refreshes <= 30) {
            LT_LOGI("TMP navigation label refresh instance=%p name=%s parent=%s"
                    " alphaBefore=%.3f forceMesh=%d",
                    instance, read_unity_object_name(instance).c_str(),
                    read_unity_parent_name(instance).c_str(), alpha_before,
                    g_tmp_force_mesh_update_info != nullptr ? 1 : 0);
        }
    }
}

void apply_translated_ui_style(void *instance, void *managed_text,
                               const UiComponentState &state) {
    if (managed_text == nullptr || !state.has_font_size || state.font_size <= 0 ||
        g_string_length == nullptr || g_string_chars == nullptr) {
        return;
    }
    int32_t length = g_string_length(managed_text);
    std::string text;
    if (length < 0 || length > 1024 * 1024 ||
        !utf16_to_utf8(g_string_chars(managed_text), length, &text)) {
        return;
    }
    size_t visible_length = count_visible_codepoints(text);
    bool has_explicit_break = limbus_text_layout::has_explicit_break(text);
    ComponentRect rect = read_component_rect(instance, g_ui_get_rect_transform_info);
    bool single_line = limbus_text_layout::is_single_line(
            has_explicit_break, rect.valid, rect.height, static_cast<float>(state.font_size),
            state.has_horizontal_overflow, state.horizontal_overflow == 0);

    if (single_line) {
        // 旧 UI.Text 没有 TMP 的单行自动缩放。根据矩形宽度直接计算安全字号，
        // 然后关闭换行和裁切，保证横幅与页签不会只剩零碎笔画。
        float scale = 1.0f;
        if (rect.valid && visible_length > 0) {
            float estimated_width = static_cast<float>(visible_length * state.font_size);
            scale = std::min(1.0f, rect.width / std::max(1.0f, estimated_width));
        }
        int32_t safe_size = std::max(7, static_cast<int32_t>(
                static_cast<float>(state.font_size) * scale));
        invoke_bool_setter(g_ui_set_resize_best_fit_info, instance, false);
        invoke_int_setter(g_ui_set_font_size_info, instance, safe_size);
        invoke_int_setter(g_ui_set_horizontal_overflow_info, instance, 1);  // Overflow.
        invoke_int_setter(g_ui_set_vertical_overflow_info, instance, 1);    // Overflow.
        if (state.has_line_spacing) {
            invoke_float_setter(g_ui_set_line_spacing_info, instance,
                                std::max(1.0f, state.line_spacing));
        }
    } else {
        // 正文保留游戏原排版，避免为了塞进一屏而缩成难以阅读的小字。
        if (state.has_resize_best_fit) {
            invoke_bool_setter(
                    g_ui_set_resize_best_fit_info, instance, state.resize_best_fit);
        }
        if (state.has_resize_min_size) {
            invoke_int_setter(
                    g_ui_set_resize_min_size_info, instance, state.resize_min_size);
        }
        if (state.has_resize_max_size) {
            invoke_int_setter(
                    g_ui_set_resize_max_size_info, instance, state.resize_max_size);
        }
        if (state.has_horizontal_overflow) {
            invoke_int_setter(g_ui_set_horizontal_overflow_info, instance,
                              state.horizontal_overflow);
        }
        if (state.has_vertical_overflow) {
            invoke_int_setter(
                    g_ui_set_vertical_overflow_info, instance, state.vertical_overflow);
        }
        if (state.has_line_spacing) {
            invoke_float_setter(g_ui_set_line_spacing_info, instance, state.line_spacing);
        }
        invoke_int_setter(g_ui_set_font_size_info, instance, state.font_size);
    }
}

void *prepare_ui_text(void *instance, void *managed_text) {
    const std::string probe_source = read_managed_string_value(managed_text);
    const char *probe_kind = known_text_probe_kind(probe_source);
    void *replacement = translate_managed_string(managed_text);
    bool translated = replacement != nullptr && is_translated_managed_string(replacement);
    log_short_text_diagnostic("UI", instance, managed_text, replacement, translated);
    if (!translated) {
        log_known_text_probe(
                probe_kind, "ui-text-not-translated", probe_source,
                read_managed_string_value(replacement), false, false, 0);
        restore_ui_component_state(instance);
        return replacement;
    }
    ensure_tmp_font();
    if (instance == nullptr || g_ui_font == nullptr) {
        log_known_text_probe(
                probe_kind, "ui-text-font-unavailable", probe_source,
                read_managed_string_value(replacement), true, false, 0);
        return replacement;
    }
    void *current_font = invoke_object_getter(g_ui_get_font_info, instance);
    if (probe_kind != nullptr) {
        const std::string current_font_name =
                current_font == nullptr ? std::string() : read_unity_object_name(current_font);
        LT_LOGI("Known UI.Text font state kind=%s instance=%p name=%s parent=%s"
                " source=%s result=%s currentFont=%p currentFontName=%s"
                " targetFont=%p missingProbeGlyphs=%s",
                probe_kind, instance,
                read_unity_object_name(instance).c_str(),
                read_unity_parent_name(instance).c_str(),
                probe_source.c_str(),
                read_managed_string_value(replacement).c_str(),
                current_font, current_font_name.c_str(), g_ui_font,
                ui_font_missing_probe_glyphs(g_ui_font).c_str());
    }
    if (current_font != g_ui_font) {
        capture_ui_component_state(instance);
        invoke_object_setter(g_ui_set_font_info, instance, g_ui_font);
        uint64_t switches = g_ui_primary_font_switches.fetch_add(
                1, std::memory_order_relaxed) + 1;
        if (switches == 1 || switches % 500 == 0) {
            LT_LOGI("UI.Text Chinese primary font switches=%llu font=%p",
                    static_cast<unsigned long long>(switches), g_ui_font);
        }
    }
    log_known_text_probe(
            probe_kind, "ui-text-font-bound", probe_source,
            read_managed_string_value(replacement), true, false, 0);
    return replacement;
}

void finalize_ui_text(void *instance, void *managed_text) {
    if (instance == nullptr || managed_text == nullptr ||
        !is_translated_managed_string(managed_text)) {
        return;
    }
    UiComponentState state;
    if (find_ui_component_state(instance, &state)) {
        apply_translated_ui_style(instance, managed_text, state);
    }
}

/**
 * Refreshes user-reported TMP controls after their panel becomes active.
 * The direct mirror-dungeon exit path populates text while the panel is still
 * inactive, and TMP may not rebuild these meshes when it is subsequently shown.
 */
void log_known_tmp_render_state(
        const std::string &kind, const char *stage, void *instance) {
    if (kind.empty() || instance == nullptr) return;
    bool active_in_hierarchy = false;
    void *game_object = invoke_object_getter(g_component_get_game_object_info, instance);
    invoke_bool_getter(
            g_game_object_get_active_in_hierarchy_info, game_object,
            &active_in_hierarchy);
    float alpha = -1.0f;
    const bool has_alpha = invoke_float_getter(
            g_tmp_get_alpha_info, instance, &alpha);
    int32_t max_visible = -1;
    int32_t first_visible = -1;
    const bool has_max_visible = invoke_int_getter(
            g_tmp_get_max_visible_info, instance, &max_visible);
    const bool has_first_visible = invoke_int_getter(
            g_tmp_get_first_visible_info, instance, &first_visible);
    float color[4] = {-1.0f, -1.0f, -1.0f, -1.0f};
    const bool has_color = invoke_color_getter(
            g_tmp_get_text_color_info, instance, color);
    void *font = g_tmp_get_font == nullptr ? nullptr :
            g_tmp_get_font(instance, g_tmp_get_font_info);
    int32_t font_style = -1;
    invoke_int_getter(g_tmp_get_font_style_info, instance, &font_style);
    int32_t character_count = -1;
    int32_t line_count = -1;
    void *text_info = invoke_object_getter(g_tmp_get_text_info_info, instance);
    if (text_info != nullptr) {
        invoke_int_getter(
                find_inherited_runtime_method(text_info, "get_characterCount", 0),
                text_info, &character_count);
        invoke_int_getter(
                find_inherited_runtime_method(text_info, "get_lineCount", 0),
                text_info, &line_count);
    }
    int32_t vertex_count = -1;
    void *mesh = invoke_object_getter(g_tmp_get_mesh_info, instance);
    if (mesh != nullptr) {
        invoke_int_getter(
                find_inherited_runtime_method(mesh, "get_vertexCount", 0),
                mesh, &vertex_count);
    }
    int32_t material_count = 0;
    void *materials = read_tmp_material_collection(instance);
    read_collection_count(materials, &material_count);
    void *first_material = material_count > 0 ?
            read_collection_item(materials, 0) : nullptr;
    LT_LOGI("Known TMP render state kind=%s stage=%s instance=%p name=%s parent=%s"
            " active=%d alpha=%d(%.3f) maxVisible=%d firstVisible=%d"
            " color=%d(%.3f,%.3f,%.3f,%.3f)"
            " font=%p fontName=%s fontStyle=%d chars=%d lines=%d vertices=%d"
            " materials=%d materialName=%s",
            kind.c_str(), stage == nullptr ? "-" : stage, instance,
            read_unity_object_name(instance).c_str(),
            read_unity_parent_name(instance).c_str(),
            active_in_hierarchy ? 1 : 0, has_alpha ? 1 : 0, alpha,
            has_max_visible ? max_visible : -1,
            has_first_visible ? first_visible : -1,
            has_color ? 1 : 0, color[0], color[1], color[2], color[3],
            font, font == nullptr ? "" : read_unity_object_name(font).c_str(),
            font_style, character_count, line_count, vertex_count,
            material_count,
            first_material == nullptr ? "" : read_unity_object_name(first_material).c_str());
}

void refresh_known_tmp_text_targets() {
    std::vector<std::pair<void *, std::string>> targets;
    pthread_mutex_lock(&g_font_state_lock);
    for (auto &entry : g_original_tmp_states) {
        TmpComponentState &state = entry.second;
        if (state.known_text_kind.empty() ||
            state.instance_weak_handle == 0 ||
            g_gchandle_get_target == nullptr ||
            g_gchandle_get_target(state.instance_weak_handle) != entry.first) {
            continue;
        }
        targets.push_back({entry.first, state.known_text_kind});
    }
    pthread_mutex_unlock(&g_font_state_lock);
    for (const auto &target : targets) {
        void *instance = target.first;
        ManagedObjectPin instance_pin(instance);
        if (!instance_pin.alive()) continue;
        if (!tmp_active_in_hierarchy(instance)) continue;
        const bool resubmitted =
                resubmit_known_tmp_text_if_empty(instance, "activation");
        static std::atomic<uint64_t> activation_probes{0};
        const uint64_t probe =
                activation_probes.fetch_add(1, std::memory_order_relaxed) + 1;
        if (resubmitted || probe <= 120) {
            log_known_tmp_render_state(target.second, "activation", instance);
            LT_LOGI("Known TMP activation refresh kind=%s instance=%p name=%s parent=%s",
                    target.second.c_str(), instance,
                    read_unity_object_name(instance).c_str(),
                    read_unity_parent_name(instance).c_str());
        }
    }
}

int32_t char_array_length(void *array) {
    int32_t count = -1;
    if (read_collection_count(array, &count) && count >= 0) return count;
    if (array == nullptr) return -1;
    // Il2CppArrayLayout on this 64-bit runtime is klass, monitor, bounds, max_length.
    int32_t *length = reinterpret_cast<int32_t *>(
            reinterpret_cast<unsigned char *>(array) + sizeof(void *) * 3);
    return length == nullptr ? -1 : *length;
}

void *managed_string_from_char_array(
        void *array, int32_t start, int32_t length) {
    const int32_t total = char_array_length(array);
    if (array == nullptr || start < 0 || length <= 0 || total < 0 ||
        start > total || length > total - start ||
        g_array_addr_with_size == nullptr || g_string_new_utf16 == nullptr) {
        return nullptr;
    }
    void *address = g_array_addr_with_size(
            array, static_cast<int32_t>(sizeof(uint16_t)), static_cast<uint32_t>(start));
    if (address == nullptr) {
        constexpr size_t kIl2CppArrayVectorOffset = sizeof(void *) * 4;
        address = reinterpret_cast<unsigned char *>(array) +
                kIl2CppArrayVectorOffset +
                static_cast<size_t>(start) * sizeof(uint16_t);
    }
    return g_string_new_utf16(
            reinterpret_cast<const uint16_t *>(address), length);
}

void invoke_tmp_char_text(
        void *instance, void *array, int32_t start, int32_t length,
        const void *method, tmp_set_text_fn original_char,
        tmp_set_text_range_fn original_range) {
    const int32_t total = char_array_length(array);
    if (total < 0) {
        if (original_range != nullptr) {
            original_range(instance, array, start, length, method);
        } else if (original_char != nullptr) {
            original_char(instance, array, method);
        }
        return;
    }
    const int32_t effective_start = original_range == nullptr ? 0 : start;
    const int32_t effective_length = original_range == nullptr ? total : length;
    void *managed_text = managed_string_from_char_array(
            array, effective_start, effective_length);
    if (managed_text == nullptr) {
        if (original_range != nullptr) {
            original_range(instance, array, start, length, method);
        } else if (original_char != nullptr) {
            original_char(instance, array, method);
        }
        return;
    }
    ManagedObjectPin text_pin(managed_text);
    if (!text_pin.alive()) {
        if (original_range != nullptr) {
            original_range(instance, array, start, length, method);
        } else if (original_char != nullptr) {
            original_char(instance, array, method);
        }
        return;
    }
    const std::string source = read_managed_string_value(managed_text);
    const char *probe_kind = known_text_probe_kind(source);
    void *replacement = prepare_tmp_text(instance, managed_text);
    if (replacement != managed_text && g_original_tmp_set_text_one != nullptr) {
        if (probe_kind != nullptr) {
            LT_LOGI("Known text renderer kind=%s renderer=TMP-char-array instance=%p"
                    " name=%s parent=%s translated=1 source=%s result=%s",
                    probe_kind, instance,
                    read_unity_object_name(instance).c_str(),
                    read_unity_parent_name(instance).c_str(),
                    source.c_str(), read_managed_string_value(replacement).c_str());
        }
        if (has_pending_tmp_rebuild(instance)) {
            void *empty = new_managed_string("");
            if (empty != nullptr) {
                g_original_tmp_set_text_one(instance, empty, g_tmp_set_text_one_info);
            }
        }
        g_original_tmp_set_text_one(instance, replacement, g_tmp_set_text_one_info);
        finalize_tmp_text(instance, replacement);
        return;
    }
    if (probe_kind != nullptr) {
        LT_LOGI("Known text renderer kind=%s renderer=TMP-char-array-pass-through"
                " instance=%p name=%s parent=%s source=%s",
                probe_kind, instance,
                read_unity_object_name(instance).c_str(),
                read_unity_parent_name(instance).c_str(), source.c_str());
    }
    if (original_range != nullptr) {
        original_range(instance, array, start, length, method);
    } else if (original_char != nullptr) {
        original_char(instance, array, method);
    }
}

void replacement_tmp_set_text_chars(
        void *instance, void *array, const void *method) {
    invoke_tmp_char_text(
            instance, array, 0, char_array_length(array), method,
            g_original_tmp_set_text_chars, nullptr);
}

void replacement_tmp_set_char_array(
        void *instance, void *array, const void *method) {
    invoke_tmp_char_text(
            instance, array, 0, char_array_length(array), method,
            g_original_tmp_set_char_array, nullptr);
}

void replacement_tmp_set_text_chars_range(
        void *instance, void *array, int32_t start, int32_t length,
        const void *method) {
    invoke_tmp_char_text(
            instance, array, start, length, method,
            nullptr, g_original_tmp_set_text_chars_range);
}

void replacement_tmp_set_char_array_range(
        void *instance, void *array, int32_t start, int32_t length,
        const void *method) {
    invoke_tmp_char_text(
            instance, array, start, length, method,
            nullptr, g_original_tmp_set_char_array_range);
}

void replacement_game_object_set_active(
        void *instance, bool active, const void *method) {
    const bool refresh_after_activation =
            active && !g_refreshing_known_tmp_targets;
    if (refresh_after_activation) {
        g_refreshing_known_tmp_targets = true;
    }
    if (g_original_game_object_set_active != nullptr) {
        g_original_game_object_set_active(instance, active, method);
    }
    if (refresh_after_activation) {
        refresh_known_tmp_text_targets();
        g_refreshing_known_tmp_targets = false;
    }
}

void replacement_tmp_set_text(void *instance, void *managed_text, const void *method) {
    void *replacement = prepare_tmp_text(instance, managed_text);
    g_original_tmp_set_text(instance, replacement, method);
    finalize_tmp_text(instance, replacement);
}

void replacement_tmp_set_text_one(void *instance, void *managed_text, const void *method) {
    void *replacement = prepare_tmp_text(instance, managed_text);
    g_original_tmp_set_text_one(instance, replacement, method);
    finalize_tmp_text(instance, replacement);
}

void replacement_ui_set_text(void *instance, void *managed_text, const void *method) {
    void *replacement = prepare_ui_text(instance, managed_text);
    g_original_ui_set_text(instance, replacement, method);
    finalize_ui_text(instance, replacement);
}

/** Legacy TextMesh is observed only so the next fix targets the real renderer. */
void replacement_text_mesh_set_text(
        void *instance, void *managed_text, const void *method) {
    if (g_original_text_mesh_set_text != nullptr) {
        const std::string source = read_managed_string_value(managed_text);
        const char *probe_kind = known_text_probe_kind(source);
        if (probe_kind != nullptr) {
            LT_LOGI("Known text renderer kind=%s renderer=TextMesh instance=%p"
                    " name=%s parent=%s sourceChars=%zu",
                    probe_kind, instance,
                    read_unity_object_name(instance).c_str(),
                    read_unity_parent_name(instance).c_str(),
                    source.size());
            log_known_text_probe(
                    probe_kind, "textmesh-passthrough", source, source,
                    false, false, 0);
        }
        g_original_text_mesh_set_text(instance, managed_text, method);
    }
}

void replacement_tmp_set_text_string(void *instance, void *managed_text, bool sync_input,
                                     const void *method) {
    void *replacement = prepare_tmp_text(instance, managed_text);
    g_original_tmp_set_text_string(instance, replacement, sync_input, method);
    finalize_tmp_text(instance, replacement);
}

int32_t replacement_get_language(void *instance, const void *method) {
    uint32_t hits = g_language_hook_hits.fetch_add(1, std::memory_order_relaxed) + 1;
    if (hits <= 5 || hits % 5000 == 0) {
        LT_LOGI("Japanese language getter hit=%u source=LocalGameOptionData instance=%p method=%p",
                hits, instance, method);
    }
    return 2;  // LOCALIZE_LANGUAGE.JP
}

int32_t replacement_global_get_language(void *instance, const void *method) {
    uint32_t hits = g_language_hook_hits.fetch_add(1, std::memory_order_relaxed) + 1;
    if (hits <= 5 || hits % 5000 == 0) {
        LT_LOGI("Japanese language getter hit=%u source=GlobalGameManager.Lang instance=%p method=%p",
                hits, instance, method);
    }
    return 2;  // LOCALIZE_LANGUAGE.JP
}

bool read_exact(FILE *file, void *buffer, size_t size) {
    return size == 0 || fread(buffer, 1, size, file) == size;
}

bool read_u32(FILE *file, uint32_t *value) {
    uint32_t encoded = 0;
    if (!read_exact(file, &encoded, sizeof(encoded))) return false;
    *value = ntohl(encoded);
    return true;
}

bool read_string(FILE *file, std::string *value) {
    uint32_t size = 0;
    if (!read_u32(file, &size) || size > kMaxStringBytes) return false;
    value->resize(size);
    return read_exact(file, size == 0 ? nullptr : &(*value)[0], size);
}

void rebuild_package_line_break_index(
        const std::unordered_map<std::string, std::string> &translations) {
    g_package_line_breaks.clear();
    std::unordered_set<std::string> ambiguous;
    const auto register_variant = [&ambiguous](const std::string &variant,
                                               const std::string &translation) {
        if (variant == translation || ambiguous.find(variant) != ambiguous.end()) return;
        auto existing = g_package_line_breaks.find(variant);
        if (existing == g_package_line_breaks.end()) {
            g_package_line_breaks.emplace(variant, translation);
        } else if (existing->second != translation) {
            // 展平形式有歧义时宁可保留游戏文本，也不能恢复成另一个人格的换行结构。
            g_package_line_breaks.erase(existing);
            ambiguous.insert(variant);
        }
    };
    for (const auto &entry : translations) {
        const std::string &translation = entry.second;
        if (translation.find('\n') == std::string::npos) continue;
        std::string spaced = translation;
        std::replace(spaced.begin(), spaced.end(), '\n', ' ');
        register_variant(spaced, translation);
        std::string compact = translation;
        compact.erase(std::remove(compact.begin(), compact.end(), '\n'), compact.end());
        register_variant(compact, translation);
    }
}

bool load_index_file(const char *path) {
    FILE *file = fopen(path, "rb");
    if (file == nullptr) {
        LT_LOGW("Index file is not readable path=%s errno=%d", path, errno);
        return false;
    }
    unsigned char magic[sizeof(kIndexMagic)] = {};
    uint32_t schema = 0;
    uint32_t count = 0;
    uint32_t term_count = 0;
    uint32_t fallback_count = 0;
    uint32_t context_count = 0;
    uint32_t battle_hint_count = 0;
    bool valid = read_exact(file, magic, sizeof(magic)) &&
                 memcmp(magic, kIndexMagic, sizeof(magic)) == 0 &&
                 read_u32(file, &schema) && schema == kIndexSchema &&
                 read_u32(file, &count) && count <= kMaxEntries &&
                 read_u32(file, &term_count) && term_count <= kMaxEntries &&
                 read_u32(file, &fallback_count) && fallback_count <= kMaxEntries &&
                 read_u32(file, &context_count) && context_count <= kMaxEntries &&
                 read_u32(file, &battle_hint_count) &&
                 battle_hint_count <= kMaxEntries;
    std::unordered_map<std::string, std::string> loaded;
    std::unordered_map<std::string, std::string> loaded_terms;
    std::unordered_map<std::string, std::string> loaded_fallback;
    std::unordered_map<std::string, std::string> loaded_context;
    std::unordered_map<std::string, std::string> loaded_battle_hint;
    if (valid) {
        loaded.reserve(count);
        for (uint32_t i = 0; i < count; ++i) {
            std::string source;
            std::string translation;
            if (!read_string(file, &source) || !read_string(file, &translation) || source.empty()) {
                valid = false;
                break;
            }
            loaded[source] = translation;
        }
        loaded_terms.reserve(term_count);
        for (uint32_t i = 0; valid && i < term_count; ++i) {
            std::string source;
            std::string translation;
            if (!read_string(file, &source) || !read_string(file, &translation) || source.empty()) {
                valid = false;
                break;
            }
            loaded_terms[source] = translation;
        }
        loaded_fallback.reserve(fallback_count);
        for (uint32_t i = 0; valid && i < fallback_count; ++i) {
            std::string source;
            std::string translation;
            if (!read_string(file, &source) || !read_string(file, &translation) || source.empty()) {
                valid = false;
                break;
            }
            loaded_fallback[source] = translation;
        }
        loaded_context.reserve(context_count);
        for (uint32_t i = 0; valid && i < context_count; ++i) {
            std::string source;
            std::string translation;
            if (!read_string(file, &source) || !read_string(file, &translation) || source.empty()) {
                valid = false;
                break;
            }
            loaded_context[source] = translation;
        }
        loaded_battle_hint.reserve(battle_hint_count);
        for (uint32_t i = 0; valid && i < battle_hint_count; ++i) {
            std::string key;
            std::string translation;
            if (!read_string(file, &key) || !read_string(file, &translation) ||
                key.empty()) {
                valid = false;
                break;
            }
            loaded_battle_hint[key] = translation;
        }
    }
    fclose(file);
    if (!valid || loaded.size() != count || loaded_terms.size() != term_count ||
        loaded_fallback.size() != fallback_count || loaded_context.size() != context_count ||
        loaded_battle_hint.size() != battle_hint_count) {
        LT_LOGW("Reject invalid translation index path=%s schema=%u "
                "declared=%u/%u/%u/%u/%u loaded=%zu/%zu/%zu/%zu/%zu",
                path, schema, count, term_count, fallback_count, context_count,
                battle_hint_count, loaded.size(), loaded_terms.size(),
                loaded_fallback.size(), loaded_context.size(),
                loaded_battle_hint.size());
        return false;
    }
    g_index.swap(loaded);
    g_terms.swap(loaded_terms);
    g_fallback_index.swap(loaded_fallback);
    g_context_index.swap(loaded_context);
    g_battle_hint_index.swap(loaded_battle_hint);
    rebuild_package_line_break_index(g_index);
    rebuild_package_line_break_index(g_fallback_index);
    rebuild_term_trie();
    LT_LOGI("Loaded Japanese full-text translation index entries=%zu terms=%zu fallback=%zu "
            "context=%zu battleHints=%zu lineBreaks=%zu path=%s",
            g_index.size(), g_terms.size(), g_fallback_index.size(),
            g_context_index.size(), g_battle_hint_index.size(),
            g_package_line_breaks.size(), path);
    return true;
}

bool load_active_index() {
    FILE *pointer = fopen(g_active_pointer, "r");
    if (pointer == nullptr) {
        LT_LOGI("No active translation index pointer path=%s", g_active_pointer);
        return false;
    }
    char index_path[PATH_MAX] = {};
    bool valid = fgets(index_path, sizeof(index_path), pointer) != nullptr;
    fclose(pointer);
    if (!valid) {
        LT_LOGW("Active translation index pointer is empty path=%s", g_active_pointer);
        return false;
    }
    index_path[strcspn(index_path, "\r\n")] = 0;
    if (index_path[0] != '/') {
        LT_LOGW("Reject non-absolute translation index path=%s", index_path);
        return false;
    }
    return load_index_file(index_path);
}

class MappedElfResolver {
public:
    MappedElfResolver() : mapping_(MAP_FAILED), size_(0), base_(0), symbols_(nullptr),
                          symbol_count_(0), strings_(nullptr), strings_size_(0) {}

    ~MappedElfResolver() {
        if (mapping_ != MAP_FAILED) munmap(mapping_, size_);
    }

    bool open(const char *path) {
        FILE *maps = fopen("/proc/self/maps", "r");
        char line[PATH_MAX + 160] = {};
        while (maps != nullptr && fgets(line, sizeof(line), maps) != nullptr) {
            unsigned long start = 0;
            unsigned long offset = 1;
            if (strstr(line, path) != nullptr &&
                sscanf(line, "%lx-%*lx %*s %lx", &start, &offset) == 2 && offset == 0) {
                if (static_cast<uintptr_t>(start) > base_) {
                    base_ = static_cast<uintptr_t>(start);
                }
            }
        }
        if (maps != nullptr) fclose(maps);
        int fd = ::open(path, O_RDONLY | O_CLOEXEC);
        struct stat state = {};
        if (base_ == 0 || fd < 0 || fstat(fd, &state) != 0 || state.st_size <= 0) {
            if (fd >= 0) close(fd);
            return false;
        }
        size_ = static_cast<size_t>(state.st_size);
        mapping_ = mmap(nullptr, size_, PROT_READ, MAP_PRIVATE, fd, 0);
        close(fd);
        if (mapping_ == MAP_FAILED || size_ < sizeof(Elf64_Ehdr)) return false;
        const Elf64_Ehdr *header = reinterpret_cast<const Elf64_Ehdr *>(mapping_);
        if (memcmp(header->e_ident, ELFMAG, SELFMAG) != 0 ||
            header->e_ident[EI_CLASS] != ELFCLASS64 || header->e_type != ET_DYN ||
            header->e_shentsize != sizeof(Elf64_Shdr) || header->e_shnum == 0 ||
            header->e_shoff > size_ ||
            header->e_shnum > (size_ - header->e_shoff) / sizeof(Elf64_Shdr)) return false;
        const Elf64_Shdr *sections = reinterpret_cast<const Elf64_Shdr *>(
                reinterpret_cast<const unsigned char *>(mapping_) + header->e_shoff);
        for (size_t i = 0; i < header->e_shnum; ++i) {
            const Elf64_Shdr &section = sections[i];
            if (section.sh_type != SHT_DYNSYM || section.sh_entsize != sizeof(Elf64_Sym) ||
                section.sh_link >= header->e_shnum || section.sh_offset > size_ ||
                section.sh_size > size_ - section.sh_offset) continue;
            const Elf64_Shdr &strings = sections[section.sh_link];
            if (strings.sh_type != SHT_STRTAB || strings.sh_offset > size_ ||
                strings.sh_size > size_ - strings.sh_offset) continue;
            symbols_ = reinterpret_cast<const Elf64_Sym *>(
                    reinterpret_cast<const unsigned char *>(mapping_) + section.sh_offset);
            symbol_count_ = section.sh_size / sizeof(Elf64_Sym);
            strings_ = reinterpret_cast<const char *>(mapping_) + strings.sh_offset;
            strings_size_ = strings.sh_size;
            return true;
        }
        return false;
    }

    template <typename T>
    T resolve(const char *name) const {
        for (size_t i = 0; i < symbol_count_; ++i) {
            const Elf64_Sym &symbol = symbols_[i];
            if (symbol.st_name >= strings_size_ || symbol.st_shndx == SHN_UNDEF) continue;
            if (strcmp(strings_ + symbol.st_name, name) == 0) {
                return reinterpret_cast<T>(base_ + symbol.st_value);
            }
        }
        return nullptr;
    }

    void **resolve_import_slot(const char *name) const {
        if (mapping_ == MAP_FAILED) return nullptr;
        const Elf64_Ehdr *header = reinterpret_cast<const Elf64_Ehdr *>(mapping_);
        const Elf64_Shdr *sections = reinterpret_cast<const Elf64_Shdr *>(
                reinterpret_cast<const unsigned char *>(mapping_) + header->e_shoff);
        for (size_t i = 0; i < header->e_shnum; ++i) {
            const Elf64_Shdr &relocations = sections[i];
            if (relocations.sh_type != SHT_RELA || relocations.sh_entsize != sizeof(Elf64_Rela) ||
                relocations.sh_link >= header->e_shnum || relocations.sh_offset > size_ ||
                relocations.sh_size > size_ - relocations.sh_offset) continue;
            const Elf64_Shdr &symbols = sections[relocations.sh_link];
            if (symbols.sh_type != SHT_DYNSYM || symbols.sh_entsize != sizeof(Elf64_Sym) ||
                symbols.sh_link >= header->e_shnum || symbols.sh_offset > size_ ||
                symbols.sh_size > size_ - symbols.sh_offset) continue;
            const Elf64_Shdr &strings = sections[symbols.sh_link];
            if (strings.sh_type != SHT_STRTAB || strings.sh_offset > size_ ||
                strings.sh_size > size_ - strings.sh_offset) continue;
            const Elf64_Rela *entries = reinterpret_cast<const Elf64_Rela *>(
                    reinterpret_cast<const unsigned char *>(mapping_) + relocations.sh_offset);
            const Elf64_Sym *symbol_table = reinterpret_cast<const Elf64_Sym *>(
                    reinterpret_cast<const unsigned char *>(mapping_) + symbols.sh_offset);
            const char *string_table = reinterpret_cast<const char *>(mapping_) + strings.sh_offset;
            size_t relocation_count = relocations.sh_size / sizeof(Elf64_Rela);
            size_t symbol_count = symbols.sh_size / sizeof(Elf64_Sym);
            for (size_t relocation = 0; relocation < relocation_count; ++relocation) {
                uint32_t type = ELF64_R_TYPE(entries[relocation].r_info);
                size_t symbol_index = ELF64_R_SYM(entries[relocation].r_info);
                if ((type != R_AARCH64_JUMP_SLOT && type != R_AARCH64_GLOB_DAT) ||
                    symbol_index >= symbol_count || symbol_table[symbol_index].st_name >= strings.sh_size) {
                    continue;
                }
                if (strcmp(string_table + symbol_table[symbol_index].st_name, name) == 0) {
                    return reinterpret_cast<void **>(base_ + entries[relocation].r_offset);
                }
            }
        }
        return nullptr;
    }

private:
    void *mapping_;
    size_t size_;
    uintptr_t base_;
    const Elf64_Sym *symbols_;
    size_t symbol_count_;
    const char *strings_;
    size_t strings_size_;
};

template <typename Fn>
static bool install_method_info_hook(const void *method_info, void *replacement, Fn *original_out) {
    if (method_info == nullptr || replacement == nullptr || original_out == nullptr) {
        return false;
    }
    void *current_pointer = nullptr;
    memcpy(&current_pointer, method_info, sizeof(current_pointer));
    if (current_pointer == nullptr) {
        return false;
    }
    if (current_pointer == replacement) {
        return *original_out != nullptr;
    }
    const long page_size = sysconf(_SC_PAGESIZE);
    if (page_size > 0) {
        uintptr_t addr = reinterpret_cast<uintptr_t>(method_info);
        uintptr_t page_start = addr & ~static_cast<uintptr_t>(page_size - 1);
        uintptr_t page_end = (addr + sizeof(void *) + static_cast<uintptr_t>(page_size - 1)) &
                ~static_cast<uintptr_t>(page_size - 1);
        mprotect(reinterpret_cast<void *>(page_start), page_end - page_start,
                 PROT_READ | PROT_WRITE);
    }
    *original_out = reinterpret_cast<Fn>(current_pointer);
    void *writable_slot = const_cast<void *>(method_info);
    memcpy(writable_slot, &replacement, sizeof(replacement));
    return true;
}

bool report_il2cpp_resolver(const MappedElfResolver &resolver, bool query_domain) {
    typedef void *(*domain_get_fn)();
    typedef const void **(*domain_get_assemblies_fn)(const void *, size_t *);
    typedef const void *(*assembly_get_image_fn)(const void *);
    typedef size_t (*image_get_class_count_fn)(const void *);
    typedef void *(*image_get_class_fn)(const void *, size_t);
    typedef const char *(*class_get_name_fn)(void *);
    typedef void *(*class_from_name_fn)(const void *, const char *, const char *);
    typedef const void *(*class_get_method_from_name_fn)(void *, const char *, int);
    typedef const void *(*class_get_methods_fn)(void *, void **);
    typedef const char *(*method_get_name_fn)(const void *);
    typedef uint32_t (*method_get_param_count_fn)(const void *);
    typedef const void *(*method_get_param_fn)(const void *, uint32_t);
    typedef const void *(*method_get_return_type_fn)(const void *);
    typedef char *(*type_get_name_fn)(const void *);
    typedef void (*il2cpp_free_fn)(void *);

    domain_get_fn domain_get = resolver.resolve<domain_get_fn>("il2cpp_domain_get");
    domain_get_assemblies_fn domain_get_assemblies =
            resolver.resolve<domain_get_assemblies_fn>("il2cpp_domain_get_assemblies");
    assembly_get_image_fn assembly_get_image =
            resolver.resolve<assembly_get_image_fn>("il2cpp_assembly_get_image");
    image_get_class_count_fn image_get_class_count =
            resolver.resolve<image_get_class_count_fn>("il2cpp_image_get_class_count");
    image_get_class_fn image_get_class =
            resolver.resolve<image_get_class_fn>("il2cpp_image_get_class");
    class_get_name_fn class_get_name =
            resolver.resolve<class_get_name_fn>("il2cpp_class_get_name");
    class_from_name_fn class_from_name =
            resolver.resolve<class_from_name_fn>("il2cpp_class_from_name");
    class_get_method_from_name_fn class_get_method_from_name =
            resolver.resolve<class_get_method_from_name_fn>("il2cpp_class_get_method_from_name");
    class_get_methods_fn class_get_methods =
            resolver.resolve<class_get_methods_fn>("il2cpp_class_get_methods");
    method_get_name_fn method_get_name =
            resolver.resolve<method_get_name_fn>("il2cpp_method_get_name");
    method_get_param_count_fn method_get_param_count =
            resolver.resolve<method_get_param_count_fn>("il2cpp_method_get_param_count");
    method_get_param_fn method_get_param =
            resolver.resolve<method_get_param_fn>("il2cpp_method_get_param");
    method_get_return_type_fn method_get_return_type =
            resolver.resolve<method_get_return_type_fn>("il2cpp_method_get_return_type");
    type_get_name_fn type_get_name = resolver.resolve<type_get_name_fn>("il2cpp_type_get_name");
    il2cpp_free_fn il2cpp_free = resolver.resolve<il2cpp_free_fn>("il2cpp_free");
    string_length_fn string_length = resolver.resolve<string_length_fn>("il2cpp_string_length");
    string_chars_fn string_chars = resolver.resolve<string_chars_fn>("il2cpp_string_chars");
    string_new_utf16_fn string_new_utf16 =
            resolver.resolve<string_new_utf16_fn>("il2cpp_string_new_utf16");
    array_addr_with_size_fn array_addr_with_size =
            resolver.resolve<array_addr_with_size_fn>("il2cpp_array_addr_with_size");
    gchandle_new_fn gchandle_new =
            resolver.resolve<gchandle_new_fn>("il2cpp_gchandle_new");
    gchandle_new_weakref_fn gchandle_new_weakref =
            resolver.resolve<gchandle_new_weakref_fn>("il2cpp_gchandle_new_weakref");
    gchandle_get_target_fn gchandle_get_target =
            resolver.resolve<gchandle_get_target_fn>("il2cpp_gchandle_get_target");
    gchandle_free_fn gchandle_free = resolver.resolve<gchandle_free_fn>("il2cpp_gchandle_free");
    runtime_invoke_fn runtime_invoke = resolver.resolve<runtime_invoke_fn>("il2cpp_runtime_invoke");
    object_get_class_fn object_get_class =
            resolver.resolve<object_get_class_fn>("il2cpp_object_get_class");
    object_unbox_fn object_unbox = resolver.resolve<object_unbox_fn>("il2cpp_object_unbox");
    class_get_parent_fn class_get_parent =
            resolver.resolve<class_get_parent_fn>("il2cpp_class_get_parent");
    class_get_fields_fn class_get_fields =
            resolver.resolve<class_get_fields_fn>("il2cpp_class_get_fields");
    field_get_name_fn field_get_name =
            resolver.resolve<field_get_name_fn>("il2cpp_field_get_name");
    field_get_type_fn field_get_type =
            resolver.resolve<field_get_type_fn>("il2cpp_field_get_type");
    field_get_value_fn field_get_value =
            resolver.resolve<field_get_value_fn>("il2cpp_field_get_value");
    field_set_value_fn field_set_value =
            resolver.resolve<field_set_value_fn>("il2cpp_field_set_value");
    if (domain_get == nullptr || domain_get_assemblies == nullptr || assembly_get_image == nullptr ||
        class_from_name == nullptr || class_get_method_from_name == nullptr) {
        LT_LOGW("IL2CPP exports missing domain=%p assemblies=%p image=%p class=%p method=%p",
                reinterpret_cast<void *>(domain_get), reinterpret_cast<void *>(domain_get_assemblies),
                reinterpret_cast<void *>(assembly_get_image), reinterpret_cast<void *>(class_from_name),
                reinterpret_cast<void *>(class_get_method_from_name));
        return false;
    }
    if (!query_domain) return false;
    void *domain = domain_get();
    size_t assembly_count = 0;
    const void **assemblies = domain == nullptr ? nullptr : domain_get_assemblies(domain, &assembly_count);
    if (assemblies == nullptr || assembly_count == 0) return false;
    const void *tmp_method = nullptr;
    const void *ui_method = nullptr;
    const void *text_mesh_method = nullptr;
    const void *game_object_set_active_method = nullptr;
    const void *tmp_set_font_method = nullptr;
    const void *tmp_get_font_method = nullptr;
    const void *create_tmp_font_method = nullptr;
    const void *create_dynamic_font_method = nullptr;
    const void *tmp_set_text_one_method = nullptr;
    const void *tmp_set_text_string_method = nullptr;
    const void *tmp_set_text_chars_method = nullptr;
    const void *tmp_set_text_chars_range_method = nullptr;
    const void *tmp_set_char_array_method = nullptr;
    const void *tmp_set_char_array_range_method = nullptr;
    void *tmp_text_class = nullptr;
    void *ui_text_class = nullptr;
    void *text_mesh_class = nullptr;
    void *game_object_class = nullptr;
    void *tmp_font_class = nullptr;
    void *unity_font_class = nullptr;
    void *unity_object_class = nullptr;
    void *component_class = nullptr;
    void *transform_class = nullptr;
    void *rect_transform_class = nullptr;
    void *material_class = nullptr;
    void *tmp_settings_class = nullptr;
    const void *get_language_method = nullptr;
    const void *get_global_language_method = nullptr;
    struct AcquisitionTarget {
        std::string name;
        void *klass;
    };
    std::vector<AcquisitionTarget> acquisition_targets;
    std::unordered_set<void *> acquisition_classes;
    for (size_t i = 0; assemblies != nullptr && i < assembly_count; ++i) {
        const void *image = assembly_get_image(assemblies[i]);
        if (image == nullptr) continue;
        if (image_get_class_count != nullptr && image_get_class != nullptr &&
            class_get_name != nullptr) {
            size_t class_count = image_get_class_count(image);
            for (size_t class_index = 0; class_index < class_count; ++class_index) {
                void *klass = image_get_class(image, class_index);
                const char *name = klass == nullptr ? nullptr : class_get_name(klass);
                if (name != nullptr && strncmp(name, "TextData_", 9) == 0 &&
                    acquisition_classes.insert(klass).second) {
                    acquisition_targets.push_back({name, klass});
                }
            }
        }
        if (tmp_method == nullptr) {
            void *klass = class_from_name(image, "TMPro", "TMP_Text");
            if (klass != nullptr) {
                tmp_text_class = klass;
                tmp_method = class_get_method_from_name(klass, "set_text", 1);
            }
        }
        if (ui_method == nullptr) {
            void *klass = class_from_name(image, "UnityEngine.UI", "Text");
            if (klass != nullptr) {
                ui_text_class = klass;
                ui_method = class_get_method_from_name(klass, "set_text", 1);
            }
        }
        if (text_mesh_method == nullptr) {
            void *klass = class_from_name(image, "UnityEngine", "TextMesh");
            if (klass != nullptr) {
                text_mesh_class = klass;
                text_mesh_method = class_get_method_from_name(klass, "set_text", 1);
            }
        }
        if (game_object_set_active_method == nullptr) {
            void *klass = class_from_name(image, "UnityEngine", "GameObject");
            if (klass != nullptr) {
                game_object_class = klass;
                game_object_set_active_method =
                        class_get_method_from_name(klass, "SetActive", 1);
            }
        }
        if (tmp_font_class == nullptr) {
            tmp_font_class = class_from_name(image, "TMPro", "TMP_FontAsset");
        }
        if (unity_font_class == nullptr) {
            unity_font_class = class_from_name(image, "UnityEngine", "Font");
        }
        if (unity_object_class == nullptr) {
            unity_object_class = class_from_name(image, "UnityEngine", "Object");
        }
        if (component_class == nullptr) {
            component_class = class_from_name(image, "UnityEngine", "Component");
        }
        if (transform_class == nullptr) {
            transform_class = class_from_name(image, "UnityEngine", "Transform");
        }
        if (rect_transform_class == nullptr) {
            rect_transform_class = class_from_name(image, "UnityEngine", "RectTransform");
        }
        if (material_class == nullptr) {
            material_class = class_from_name(image, "UnityEngine", "Material");
        }
        if (tmp_settings_class == nullptr) {
            tmp_settings_class = class_from_name(image, "TMPro", "TMP_Settings");
        }
        if (get_language_method == nullptr) {
            void *klass = class_from_name(image, "LocalSave", "LocalGameOptionData");
            if (klass != nullptr) {
                get_language_method = class_get_method_from_name(klass, "GetLanguage", 0);
            }
        }
        if (get_global_language_method == nullptr) {
            void *klass = class_from_name(image, "", "GlobalGameManager");
            if (klass != nullptr) {
                get_global_language_method = class_get_method_from_name(klass, "get_Lang", 0);
            }
        }
    }
    std::sort(acquisition_targets.begin(), acquisition_targets.end(),
              [](const AcquisitionTarget &left, const AcquisitionTarget &right) {
                  return left.name < right.name;
              });
    LT_LOGI("Discovered TextData formatter classes=%zu", acquisition_targets.size());
    void *tmp_pointer = nullptr;
    void *ui_pointer = nullptr;
    void *text_mesh_pointer = nullptr;
    void *game_object_set_active_pointer = nullptr;
    if (tmp_method != nullptr) memcpy(&tmp_pointer, tmp_method, sizeof(tmp_pointer));
    if (ui_method != nullptr) memcpy(&ui_pointer, ui_method, sizeof(ui_pointer));
    if (text_mesh_method != nullptr) {
        memcpy(&text_mesh_pointer, text_mesh_method, sizeof(text_mesh_pointer));
    }
    if (game_object_set_active_method != nullptr) {
        memcpy(&game_object_set_active_pointer, game_object_set_active_method,
               sizeof(game_object_set_active_pointer));
    }
    LT_LOGI("IL2CPP diagnostic assemblies=%zu TMP_Text.set_text info=%p pointer=%p"
            " UI.Text.set_text info=%p pointer=%p"
            " TextMesh.set_text info=%p pointer=%p"
            " GameObject.SetActive info=%p pointer=%p indexEntries=%zu",
            assembly_count, tmp_method, tmp_pointer, ui_method, ui_pointer,
            text_mesh_method, text_mesh_pointer, game_object_set_active_method,
            game_object_set_active_pointer, g_index.size());
    if (class_get_methods != nullptr && method_get_name != nullptr &&
        method_get_param_count != nullptr && method_get_param != nullptr &&
        type_get_name != nullptr && il2cpp_free != nullptr) {
        struct TargetClass { const char *label; void *klass; };
        TargetClass targets[] = {
                {"TMP_Text", tmp_text_class},
                {"TMP_FontAsset", tmp_font_class},
                {"UnityEngine.Font", unity_font_class},
                {"UnityEngine.TextMesh", text_mesh_class},
                {"UnityEngine.GameObject", game_object_class},
        };
        const char *interesting[] = {
                "set_font", "get_font", "CreateFontAsset", "CreateDynamicFontFromOSFont",
                "SetText", "SetCharArray", "get_material", "get_materials",
                "get_materialArray", "get_MaterialArray", "get_fontMaterial",
                "get_fontMaterials", "get_fontSharedMaterial",
                "get_fontSharedMaterials", "get_fontStyle", "set_fontStyle",
                "HasCharacter", "TryAddCharacters", "TryAddCharacterInternal"
        };
        for (const TargetClass &target : targets) {
            void *iterator = nullptr;
            const void *method = nullptr;
            while (target.klass != nullptr && (method = class_get_methods(target.klass, &iterator)) != nullptr) {
                const char *method_name = method_get_name(method);
                bool matched = false;
                for (const char *name : interesting) {
                    if (method_name != nullptr && strcmp(method_name, name) == 0) matched = true;
                }
                if (!matched) continue;
                uint32_t parameter_count = method_get_param_count(method);
                std::string signature;
                for (uint32_t parameter = 0; parameter < parameter_count; ++parameter) {
                    char *type_name = type_get_name(method_get_param(method, parameter));
                    if (parameter > 0) signature += ",";
                    signature += type_name == nullptr ? "?" : type_name;
                    if (type_name != nullptr) il2cpp_free(type_name);
                }
                void *pointer = nullptr;
                memcpy(&pointer, method, sizeof(pointer));
                LT_LOGI("IL2CPP method inventory class=%s method=%s params=(%s) info=%p pointer=%p",
                        target.label, method_name, signature.c_str(), method, pointer);
                if (target.klass == tmp_font_class && strcmp(method_name, "CreateFontAsset") == 0 &&
                    signature.find("System.String,") == 0 && parameter_count == 9) {
                    create_tmp_font_method = method;
                }
                if (target.klass == unity_font_class &&
                    strcmp(method_name, "CreateDynamicFontFromOSFont") == 0 &&
                    signature == "System.String,System.Int32") {
                    create_dynamic_font_method = method;
                }
                if (target.klass == tmp_text_class && strcmp(method_name, "SetText") == 0 &&
                    signature == "System.String") {
                    tmp_set_text_one_method = method;
                }
                if (target.klass == tmp_text_class && strcmp(method_name, "SetText") == 0 &&
                    signature == "System.String,System.Boolean") {
                    tmp_set_text_string_method = method;
                }
                if (target.klass == tmp_text_class && strcmp(method_name, "SetText") == 0 &&
                    signature == "System.Char[]") {
                    tmp_set_text_chars_method = method;
                }
                if (target.klass == tmp_text_class && strcmp(method_name, "SetText") == 0 &&
                    signature == "System.Char[],System.Int32,System.Int32") {
                    tmp_set_text_chars_range_method = method;
                }
                if (target.klass == tmp_text_class &&
                    strcmp(method_name, "SetCharArray") == 0 &&
                    signature == "System.Char[]") {
                    tmp_set_char_array_method = method;
                }
                if (target.klass == tmp_text_class &&
                    strcmp(method_name, "SetCharArray") == 0 &&
                    signature == "System.Char[],System.Int32,System.Int32") {
                    tmp_set_char_array_range_method = method;
                }
            }
        }
    }
    if (tmp_text_class != nullptr) {
        tmp_set_font_method = class_get_method_from_name(tmp_text_class, "set_font", 1);
        tmp_get_font_method = class_get_method_from_name(tmp_text_class, "get_font", 0);
    }
    auto find_method_by_signature = [&](void *klass, const char *expected_name,
                                        const char *expected_signature) -> const void * {
        if (klass == nullptr || class_get_methods == nullptr || method_get_name == nullptr ||
            method_get_param_count == nullptr || method_get_param == nullptr ||
            type_get_name == nullptr || il2cpp_free == nullptr) {
            return nullptr;
        }
        void *iterator = nullptr;
        const void *method = nullptr;
        while ((method = class_get_methods(klass, &iterator)) != nullptr) {
            const char *method_name = method_get_name(method);
            if (method_name == nullptr || strcmp(method_name, expected_name) != 0) continue;
            std::string signature;
            uint32_t parameter_count = method_get_param_count(method);
            for (uint32_t parameter = 0; parameter < parameter_count; ++parameter) {
                char *type_name = type_get_name(method_get_param(method, parameter));
                if (parameter > 0) signature += ",";
                signature += type_name == nullptr ? "?" : type_name;
                if (type_name != nullptr) il2cpp_free(type_name);
            }
            if (signature == expected_signature) return method;
        }
        return nullptr;
    };
    auto find_inherited_method = [&](void *klass, const char *method_name,
                                     int32_t argument_count) -> const void * {
        if (method_name == nullptr || class_get_method_from_name == nullptr) {
            return nullptr;
        }
        for (int32_t depth = 0; klass != nullptr && depth < 16; ++depth) {
            const void *method =
                    class_get_method_from_name(klass, method_name, argument_count);
            if (method != nullptr) return method;
            if (class_get_parent == nullptr) break;
            klass = class_get_parent(klass);
        }
        return nullptr;
    };
    const void *ui_font_has_character_method = find_method_by_signature(
            unity_font_class, "HasCharacter", "System.Char");
    if (ui_font_has_character_method == nullptr && unity_font_class != nullptr) {
        ui_font_has_character_method =
                class_get_method_from_name(unity_font_class, "HasCharacter", 1);
    }
    const void *tmp_font_try_add_characters_method = find_method_by_signature(
            tmp_font_class, "TryAddCharacters",
            "System.String,System.Boolean");
    const void *tmp_get_font_material_method = tmp_text_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_text_class, "get_fontMaterial", 0);
    const void *tmp_set_font_material_method = tmp_text_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_text_class, "set_fontMaterial", 1);
    const void *tmp_font_get_material_method = tmp_font_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_font_class, "get_material", 0);
    const void *tmp_font_get_materials_method = tmp_font_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_font_class, "get_materials", 0);
    const void *tmp_font_has_character_method = tmp_font_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_font_class, "HasCharacter", 1);
    const void *tmp_get_font_materials_method = tmp_text_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_text_class, "get_fontMaterials", 0);
    if (tmp_get_font_materials_method == nullptr && tmp_text_class != nullptr) {
        tmp_get_font_materials_method = class_get_method_from_name(
                tmp_text_class, "get_fontSharedMaterials", 0);
    }
    const void *tmp_get_shared_material_method = tmp_text_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_text_class, "get_fontSharedMaterial", 0);
    const void *tmp_set_shared_material_method = tmp_text_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_text_class, "set_fontSharedMaterial", 1);
    const void *tmp_get_line_spacing_method = tmp_text_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_text_class, "get_lineSpacing", 0);
    const void *tmp_set_line_spacing_method = tmp_text_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_text_class, "set_lineSpacing", 1);
    const void *tmp_get_font_size_method = tmp_text_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_text_class, "get_fontSize", 0);
    const void *tmp_set_font_size_method = tmp_text_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_text_class, "set_fontSize", 1);
    const void *tmp_get_font_size_min_method = tmp_text_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_text_class, "get_fontSizeMin", 0);
    const void *tmp_set_font_size_min_method = tmp_text_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_text_class, "set_fontSizeMin", 1);
    const void *tmp_get_font_size_max_method = tmp_text_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_text_class, "get_fontSizeMax", 0);
    const void *tmp_set_font_size_max_method = tmp_text_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_text_class, "set_fontSizeMax", 1);
    const void *tmp_get_auto_sizing_method = tmp_text_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_text_class, "get_enableAutoSizing", 0);
    const void *tmp_set_auto_sizing_method = tmp_text_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_text_class, "set_enableAutoSizing", 1);
    const void *tmp_get_word_wrapping_method = tmp_text_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_text_class, "get_enableWordWrapping", 0);
    const void *tmp_set_word_wrapping_method = tmp_text_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_text_class, "set_enableWordWrapping", 1);
    const void *tmp_get_overflow_mode_method = tmp_text_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_text_class, "get_overflowMode", 0);
    const void *tmp_set_overflow_mode_method = tmp_text_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_text_class, "set_overflowMode", 1);
    const void *tmp_get_font_style_method = tmp_text_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_text_class, "get_fontStyle", 0);
    const void *tmp_set_font_style_method = tmp_text_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_text_class, "set_fontStyle", 1);
    const void *tmp_get_alpha_method = tmp_text_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_text_class, "get_alpha", 0);
    const void *tmp_set_alpha_method = tmp_text_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_text_class, "set_alpha", 1);
    const void *tmp_get_max_visible_method = tmp_text_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_text_class, "get_maxVisibleCharacters", 0);
    const void *tmp_set_max_visible_method = tmp_text_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_text_class, "set_maxVisibleCharacters", 1);
    const void *tmp_get_first_visible_method = tmp_text_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_text_class, "get_firstVisibleCharacter", 0);
    const void *tmp_set_first_visible_method = tmp_text_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_text_class, "set_firstVisibleCharacter", 1);
    const void *tmp_get_text_color_method = find_inherited_method(
            tmp_text_class, "get_color", 0);
    const void *tmp_set_text_color_method = find_inherited_method(
            tmp_text_class, "set_color", 1);
    const void *tmp_get_text_color_gradient_method = find_inherited_method(
            tmp_text_class, "get_colorGradient", 0);
    const void *tmp_set_text_color_gradient_method = find_inherited_method(
            tmp_text_class, "set_colorGradient", 1);
    const void *tmp_get_enable_vertex_gradient_method = find_inherited_method(
            tmp_text_class, "get_enableVertexGradient", 0);
    const void *tmp_set_enable_vertex_gradient_method = find_inherited_method(
            tmp_text_class, "set_enableVertexGradient", 1);
    int32_t tmp_force_mesh_update_arg_count = 0;
    const void *tmp_force_mesh_update_method = nullptr;
    if (tmp_text_class != nullptr) {
        tmp_force_mesh_update_method =
                class_get_method_from_name(tmp_text_class, "ForceMeshUpdate", 2);
        if (tmp_force_mesh_update_method != nullptr) {
            tmp_force_mesh_update_arg_count = 2;
        } else {
            tmp_force_mesh_update_method =
                    class_get_method_from_name(tmp_text_class, "ForceMeshUpdate", 1);
            if (tmp_force_mesh_update_method != nullptr) {
                tmp_force_mesh_update_arg_count = 1;
            } else {
                tmp_force_mesh_update_method =
                        class_get_method_from_name(tmp_text_class, "ForceMeshUpdate", 0);
            }
        }
    }
    const void *tmp_get_rect_transform_method = tmp_text_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_text_class, "get_rectTransform", 0);
    const void *tmp_get_text_info_method = tmp_text_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_text_class, "get_textInfo", 0);
    const void *tmp_get_mesh_method = tmp_text_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_text_class, "get_mesh", 0);
    const void *tmp_get_text_value_method = tmp_text_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_text_class, "get_text", 0);
    const void *component_get_game_object_method = component_class == nullptr ? nullptr :
            class_get_method_from_name(component_class, "get_gameObject", 0);
    const void *game_object_get_active_in_hierarchy_method =
            game_object_class == nullptr ? nullptr :
            class_get_method_from_name(
                    game_object_class, "get_activeInHierarchy", 0);
    const void *ui_get_font_method = ui_text_class == nullptr ? nullptr :
            class_get_method_from_name(ui_text_class, "get_font", 0);
    const void *ui_set_font_method = ui_text_class == nullptr ? nullptr :
            class_get_method_from_name(ui_text_class, "set_font", 1);
    const void *ui_get_line_spacing_method = ui_text_class == nullptr ? nullptr :
            class_get_method_from_name(ui_text_class, "get_lineSpacing", 0);
    const void *ui_set_line_spacing_method = ui_text_class == nullptr ? nullptr :
            class_get_method_from_name(ui_text_class, "set_lineSpacing", 1);
    const void *ui_get_font_size_method = ui_text_class == nullptr ? nullptr :
            class_get_method_from_name(ui_text_class, "get_fontSize", 0);
    const void *ui_set_font_size_method = ui_text_class == nullptr ? nullptr :
            class_get_method_from_name(ui_text_class, "set_fontSize", 1);
    const void *ui_get_resize_min_size_method = ui_text_class == nullptr ? nullptr :
            class_get_method_from_name(ui_text_class, "get_resizeTextMinSize", 0);
    const void *ui_set_resize_min_size_method = ui_text_class == nullptr ? nullptr :
            class_get_method_from_name(ui_text_class, "set_resizeTextMinSize", 1);
    const void *ui_get_resize_max_size_method = ui_text_class == nullptr ? nullptr :
            class_get_method_from_name(ui_text_class, "get_resizeTextMaxSize", 0);
    const void *ui_set_resize_max_size_method = ui_text_class == nullptr ? nullptr :
            class_get_method_from_name(ui_text_class, "set_resizeTextMaxSize", 1);
    const void *ui_get_resize_best_fit_method = ui_text_class == nullptr ? nullptr :
            class_get_method_from_name(ui_text_class, "get_resizeTextForBestFit", 0);
    const void *ui_set_resize_best_fit_method = ui_text_class == nullptr ? nullptr :
            class_get_method_from_name(ui_text_class, "set_resizeTextForBestFit", 1);
    const void *ui_get_horizontal_overflow_method = ui_text_class == nullptr ? nullptr :
            class_get_method_from_name(ui_text_class, "get_horizontalOverflow", 0);
    const void *ui_set_horizontal_overflow_method = ui_text_class == nullptr ? nullptr :
            class_get_method_from_name(ui_text_class, "set_horizontalOverflow", 1);
    const void *ui_get_vertical_overflow_method = ui_text_class == nullptr ? nullptr :
            class_get_method_from_name(ui_text_class, "get_verticalOverflow", 0);
    const void *ui_set_vertical_overflow_method = ui_text_class == nullptr ? nullptr :
            class_get_method_from_name(ui_text_class, "set_verticalOverflow", 1);
    const void *ui_get_rect_transform_method = ui_text_class == nullptr ? nullptr :
            class_get_method_from_name(ui_text_class, "get_rectTransform", 0);
    const void *rect_transform_get_rect_method = rect_transform_class == nullptr ? nullptr :
            class_get_method_from_name(rect_transform_class, "get_rect", 0);
    const void *object_get_name_method = unity_object_class == nullptr ? nullptr :
            class_get_method_from_name(unity_object_class, "get_name", 0);
    const void *component_get_transform_method = component_class == nullptr ? nullptr :
            class_get_method_from_name(component_class, "get_transform", 0);
    const void *transform_get_parent_method = transform_class == nullptr ? nullptr :
            class_get_method_from_name(transform_class, "get_parent", 0);
    const void *material_get_float_method = find_method_by_signature(
            material_class, "GetFloat", "System.String");
    const void *material_set_float_method = find_method_by_signature(
            material_class, "SetFloat", "System.String,System.Single");
    const void *material_get_color_method = find_method_by_signature(
            material_class, "GetColor", "System.String");
    const void *material_set_color_method = find_method_by_signature(
            material_class, "SetColor", "System.String,UnityEngine.Color");
    const void *material_get_texture_method = find_method_by_signature(
            material_class, "GetTexture", "System.String");
    const void *material_set_texture_method = find_method_by_signature(
            material_class, "SetTexture", "System.String,UnityEngine.Texture");
    const void *material_enable_keyword_method = find_method_by_signature(
            material_class, "EnableKeyword", "System.String");
    const void *material_disable_keyword_method = find_method_by_signature(
            material_class, "DisableKeyword", "System.String");
    const void *material_is_keyword_enabled_method = find_method_by_signature(
            material_class, "IsKeywordEnabled", "System.String");
    const void *unity_object_instantiate_method = find_method_by_signature(
            unity_object_class, "Instantiate", "UnityEngine.Object");
    const void *get_fallback_fonts_method = tmp_settings_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_settings_class, "get_fallbackFontAssets", 0);
    LT_LOGI("TMP fallback getter class=%p method=%p", tmp_settings_class, get_fallback_fonts_method);
    LT_LOGI("TMP style methods material=%p fontAssetMaterial=%p/%p hasCharacter=%p"
            " forceMesh=%p/%d"
            " fontMaterial=%p/%p shared=%p/%p spacing=%p/%p"
            " fontSize=%p/%p min=%p/%p max=%p/%p auto=%p/%p wrap=%p/%p overflow=%p/%p"
            " style=%p/%p rect=%p/%p outline=%p/%p/%p/%p",
            material_class, tmp_font_get_material_method, tmp_font_get_materials_method,
            tmp_font_has_character_method,
            tmp_force_mesh_update_method, tmp_force_mesh_update_arg_count,
            tmp_get_font_material_method, tmp_set_font_material_method,
            tmp_get_shared_material_method,
            tmp_set_shared_material_method, tmp_get_line_spacing_method,
            tmp_set_line_spacing_method, tmp_get_font_size_method, tmp_set_font_size_method,
            tmp_get_font_size_min_method, tmp_set_font_size_min_method,
            tmp_get_font_size_max_method, tmp_set_font_size_max_method,
            tmp_get_auto_sizing_method, tmp_set_auto_sizing_method,
            tmp_get_word_wrapping_method, tmp_set_word_wrapping_method,
            tmp_get_overflow_mode_method, tmp_set_overflow_mode_method,
            tmp_get_font_style_method, tmp_set_font_style_method,
            tmp_get_rect_transform_method, rect_transform_get_rect_method,
            material_get_float_method, material_set_float_method,
            material_get_color_method, material_set_color_method);
    LT_LOGI("UI.Text style methods font=%p/%p hasCharacter=%p"
            " spacing=%p/%p fontSize=%p/%p min=%p/%p"
            " max=%p/%p bestFit=%p/%p horizontal=%p/%p vertical=%p/%p rect=%p/%p",
            ui_get_font_method, ui_set_font_method,
            ui_font_has_character_method,
            ui_get_line_spacing_method, ui_set_line_spacing_method,
            ui_get_font_size_method, ui_set_font_size_method,
            ui_get_resize_min_size_method, ui_set_resize_min_size_method,
            ui_get_resize_max_size_method, ui_set_resize_max_size_method,
            ui_get_resize_best_fit_method, ui_set_resize_best_fit_method,
            ui_get_horizontal_overflow_method, ui_set_horizontal_overflow_method,
            ui_get_vertical_overflow_method, ui_set_vertical_overflow_method,
            ui_get_rect_transform_method, rect_transform_get_rect_method);
    LT_LOGI("TMP vertex style methods color=%p/%p gradient=%p/%p enabled=%p/%p",
            tmp_get_text_color_method, tmp_set_text_color_method,
            tmp_get_text_color_gradient_method, tmp_set_text_color_gradient_method,
            tmp_get_enable_vertex_gradient_method,
            tmp_set_enable_vertex_gradient_method);
    LT_LOGI("Unity hierarchy methods name=%p transform=%p parent=%p",
            object_get_name_method, component_get_transform_method,
            transform_get_parent_method);
    if (tmp_font_class != nullptr && create_tmp_font_method == nullptr) {
        create_tmp_font_method = class_get_method_from_name(tmp_font_class, "CreateFontAsset", 9);
    }
    if (unity_font_class != nullptr && create_dynamic_font_method == nullptr) {
        create_dynamic_font_method =
                class_get_method_from_name(unity_font_class, "CreateDynamicFontFromOSFont", 2);
    }
    void *tmp_set_font_pointer = nullptr;
    void *tmp_get_font_pointer = nullptr;
    void *create_tmp_font_pointer = nullptr;
    void *create_dynamic_font_pointer = nullptr;
    void *get_language_pointer = nullptr;
    void *get_global_language_pointer = nullptr;
    if (tmp_set_font_method != nullptr) memcpy(&tmp_set_font_pointer, tmp_set_font_method, sizeof(void *));
    if (tmp_get_font_method != nullptr) memcpy(&tmp_get_font_pointer, tmp_get_font_method, sizeof(void *));
    if (create_tmp_font_method != nullptr) memcpy(&create_tmp_font_pointer, create_tmp_font_method, sizeof(void *));
    if (create_dynamic_font_method != nullptr) memcpy(&create_dynamic_font_pointer, create_dynamic_font_method, sizeof(void *));
    if (get_language_method != nullptr) memcpy(&get_language_pointer, get_language_method, sizeof(void *));
    if (get_global_language_method != nullptr) {
        memcpy(&get_global_language_pointer, get_global_language_method, sizeof(void *));
    }
    bool hook_ready = tmp_pointer != nullptr && tmp_set_font_pointer != nullptr &&
            tmp_get_font_pointer != nullptr &&
            create_tmp_font_pointer != nullptr && create_dynamic_font_pointer != nullptr &&
            get_global_language_pointer != nullptr &&
            string_length != nullptr && string_chars != nullptr && string_new_utf16 != nullptr &&
            gchandle_new != nullptr &&
            gchandle_new_weakref != nullptr && gchandle_get_target != nullptr && gchandle_free != nullptr &&
            runtime_invoke != nullptr && object_get_class != nullptr && object_unbox != nullptr;
    LT_LOGI("Translation provenance strongHandle=%d weakHandles=%d capacity=%zu",
            gchandle_new != nullptr,
            gchandle_new_weakref != nullptr && gchandle_get_target != nullptr && gchandle_free != nullptr,
            kMaxTranslatedOrigins);
    LT_LOGI("TMP array material accessor=%p",
            reinterpret_cast<void *>(array_addr_with_size));
    if (hook_ready && !g_hook_installed) {
        g_string_length = string_length;
        g_string_chars = string_chars;
        g_string_new_utf16 = string_new_utf16;
        g_array_addr_with_size = array_addr_with_size;
        g_gchandle_new = gchandle_new;
        g_gchandle_new_weakref = gchandle_new_weakref;
        g_gchandle_get_target = gchandle_get_target;
        g_gchandle_free = gchandle_free;
        g_runtime_invoke = runtime_invoke;
        g_object_get_class = object_get_class;
        g_object_unbox = object_unbox;
        g_class_get_method = class_get_method_from_name;
        g_class_get_parent = class_get_parent;
        g_class_get_fields = class_get_fields;
        g_field_get_name = field_get_name;
        g_field_get_type = field_get_type;
        g_field_get_value = field_get_value;
        g_field_set_value = field_set_value;
        g_type_get_name = type_get_name;
        g_il2cpp_free = il2cpp_free;
        g_create_dynamic_font = reinterpret_cast<create_dynamic_font_fn>(create_dynamic_font_pointer);
        g_create_tmp_font = reinterpret_cast<create_tmp_font_fn>(create_tmp_font_pointer);
        g_tmp_set_font = reinterpret_cast<tmp_set_font_fn>(tmp_set_font_pointer);
        g_tmp_get_font = reinterpret_cast<tmp_get_font_fn>(tmp_get_font_pointer);
        g_create_dynamic_font_info = create_dynamic_font_method;
        g_create_tmp_font_info = create_tmp_font_method;
        g_tmp_set_font_info = tmp_set_font_method;
        g_tmp_get_font_info = tmp_get_font_method;
        g_tmp_set_text_one_info = tmp_set_text_one_method;
        g_get_fallback_fonts_info = get_fallback_fonts_method;
        g_tmp_font_get_material_info = tmp_font_get_material_method;
        g_tmp_font_get_materials_info = tmp_font_get_materials_method;
        g_tmp_font_has_character_info = tmp_font_has_character_method;
        g_tmp_font_try_add_characters_info = tmp_font_try_add_characters_method;
        g_tmp_get_font_material_info = tmp_get_font_material_method;
        g_tmp_set_font_material_info = tmp_set_font_material_method;
        g_tmp_get_font_materials_info = tmp_get_font_materials_method;
        g_tmp_get_shared_material_info = tmp_get_shared_material_method;
        g_tmp_set_shared_material_info = tmp_set_shared_material_method;
        g_tmp_get_line_spacing_info = tmp_get_line_spacing_method;
        g_tmp_set_line_spacing_info = tmp_set_line_spacing_method;
        g_tmp_get_font_size_info = tmp_get_font_size_method;
        g_tmp_set_font_size_info = tmp_set_font_size_method;
        g_tmp_get_font_size_min_info = tmp_get_font_size_min_method;
        g_tmp_set_font_size_min_info = tmp_set_font_size_min_method;
        g_tmp_get_font_size_max_info = tmp_get_font_size_max_method;
        g_tmp_set_font_size_max_info = tmp_set_font_size_max_method;
        g_tmp_get_auto_sizing_info = tmp_get_auto_sizing_method;
        g_tmp_set_auto_sizing_info = tmp_set_auto_sizing_method;
        g_tmp_get_word_wrapping_info = tmp_get_word_wrapping_method;
        g_tmp_set_word_wrapping_info = tmp_set_word_wrapping_method;
        g_tmp_get_overflow_mode_info = tmp_get_overflow_mode_method;
        g_tmp_set_overflow_mode_info = tmp_set_overflow_mode_method;
        g_tmp_get_font_style_info = tmp_get_font_style_method;
        g_tmp_set_font_style_info = tmp_set_font_style_method;
        g_tmp_get_alpha_info = tmp_get_alpha_method;
        g_tmp_set_alpha_info = tmp_set_alpha_method;
        g_tmp_get_max_visible_info = tmp_get_max_visible_method;
        g_tmp_set_max_visible_info = tmp_set_max_visible_method;
        g_tmp_get_first_visible_info = tmp_get_first_visible_method;
        g_tmp_set_first_visible_info = tmp_set_first_visible_method;
        g_tmp_get_text_color_info = tmp_get_text_color_method;
        g_tmp_set_text_color_info = tmp_set_text_color_method;
        g_tmp_get_text_color_gradient_info = tmp_get_text_color_gradient_method;
        g_tmp_set_text_color_gradient_info = tmp_set_text_color_gradient_method;
        g_tmp_get_enable_vertex_gradient_info =
                tmp_get_enable_vertex_gradient_method;
        g_tmp_set_enable_vertex_gradient_info =
                tmp_set_enable_vertex_gradient_method;
        g_tmp_force_mesh_update_info = tmp_force_mesh_update_method;
        g_tmp_force_mesh_update_arg_count = tmp_force_mesh_update_arg_count;
        g_tmp_get_rect_transform_info = tmp_get_rect_transform_method;
        g_tmp_get_text_info_info = tmp_get_text_info_method;
        g_tmp_get_mesh_info = tmp_get_mesh_method;
        g_tmp_get_text_value_info = tmp_get_text_value_method;
        g_ui_get_font_info = ui_get_font_method;
        g_ui_set_font_info = ui_set_font_method;
        g_ui_font_has_character_info = ui_font_has_character_method;
        g_ui_get_line_spacing_info = ui_get_line_spacing_method;
        g_ui_set_line_spacing_info = ui_set_line_spacing_method;
        g_ui_get_font_size_info = ui_get_font_size_method;
        g_ui_set_font_size_info = ui_set_font_size_method;
        g_ui_get_resize_min_size_info = ui_get_resize_min_size_method;
        g_ui_set_resize_min_size_info = ui_set_resize_min_size_method;
        g_ui_get_resize_max_size_info = ui_get_resize_max_size_method;
        g_ui_set_resize_max_size_info = ui_set_resize_max_size_method;
        g_ui_get_resize_best_fit_info = ui_get_resize_best_fit_method;
        g_ui_set_resize_best_fit_info = ui_set_resize_best_fit_method;
        g_ui_get_horizontal_overflow_info = ui_get_horizontal_overflow_method;
        g_ui_set_horizontal_overflow_info = ui_set_horizontal_overflow_method;
        g_ui_get_vertical_overflow_info = ui_get_vertical_overflow_method;
        g_ui_set_vertical_overflow_info = ui_set_vertical_overflow_method;
        g_ui_get_rect_transform_info = ui_get_rect_transform_method;
        g_rect_transform_get_rect_info = rect_transform_get_rect_method;
        g_object_get_name_info = object_get_name_method;
        g_component_get_transform_info = component_get_transform_method;
        g_component_get_game_object_info = component_get_game_object_method;
        g_game_object_get_active_in_hierarchy_info =
                game_object_get_active_in_hierarchy_method;
        g_transform_get_parent_info = transform_get_parent_method;
        g_material_get_float_info = material_get_float_method;
        g_material_set_float_info = material_set_float_method;
        g_material_get_color_info = material_get_color_method;
        g_material_set_color_info = material_set_color_method;
        g_material_get_texture_info = material_get_texture_method;
        g_material_set_texture_info = material_set_texture_method;
        g_material_enable_keyword_info = material_enable_keyword_method;
        g_material_disable_keyword_info = material_disable_keyword_method;
        g_material_is_keyword_enabled_info = material_is_keyword_enabled_method;
        g_unity_object_instantiate_info = unity_object_instantiate_method;
        MSHookFunction(tmp_pointer, reinterpret_cast<void *>(replacement_tmp_set_text),
                       reinterpret_cast<void **>(&g_original_tmp_set_text));
        auto has_inline_hook_space = [&](void *klass, void *pointer) {
            if (klass == nullptr || pointer == nullptr || class_get_methods == nullptr) return false;
            std::vector<uintptr_t> pointers;
            void *iterator = nullptr;
            const void *candidate = nullptr;
            while ((candidate = class_get_methods(klass, &iterator)) != nullptr) {
                void *candidate_pointer = nullptr;
                memcpy(&candidate_pointer, candidate, sizeof(candidate_pointer));
                if (candidate_pointer != nullptr) {
                    pointers.push_back(reinterpret_cast<uintptr_t>(candidate_pointer));
                }
            }
            std::sort(pointers.begin(), pointers.end());
            pointers.erase(std::unique(pointers.begin(), pointers.end()), pointers.end());
            uintptr_t address = reinterpret_cast<uintptr_t>(pointer);
            auto position = std::lower_bound(pointers.begin(), pointers.end(), address);
            return position != pointers.end() && *position == address &&
                    (position + 1 == pointers.end() || *(position + 1) - address >= 16);
        };
        if (tmp_set_font_pointer != nullptr &&
            has_inline_hook_space(tmp_text_class, tmp_set_font_pointer)) {
            void *hooked_tmp_set_font = nullptr;
            MSHookFunction(tmp_set_font_pointer,
                           reinterpret_cast<void *>(replacement_tmp_set_font),
                           &hooked_tmp_set_font);
            if (hooked_tmp_set_font != nullptr) {
                g_tmp_set_font = reinterpret_cast<tmp_set_font_fn>(hooked_tmp_set_font);
            }
            LT_LOGI("Installed TMP_Text.set_font hook target=%p original=%p",
                    tmp_set_font_pointer, hooked_tmp_set_font);
        } else if (tmp_set_font_pointer != nullptr) {
            LT_LOGW("Skip unsafe adjacent TMP_Text.set_font pointer=%p",
                    tmp_set_font_pointer);
        }
        void *set_shared_material_pointer = nullptr;
        if (tmp_set_shared_material_method != nullptr) {
            memcpy(&set_shared_material_pointer, tmp_set_shared_material_method,
                   sizeof(set_shared_material_pointer));
        }
        if (set_shared_material_pointer != nullptr &&
            has_inline_hook_space(tmp_text_class, set_shared_material_pointer)) {
            MSHookFunction(set_shared_material_pointer,
                           reinterpret_cast<void *>(replacement_tmp_set_shared_material),
                           reinterpret_cast<void **>(&g_original_tmp_set_shared_material));
        } else if (set_shared_material_pointer != nullptr) {
            LT_LOGW("Skip unsafe adjacent TMP shared material setter=%p",
                    set_shared_material_pointer);
        }
        void *set_material_pointer = nullptr;
        if (tmp_set_font_material_method != nullptr) {
            memcpy(&set_material_pointer, tmp_set_font_material_method,
                   sizeof(set_material_pointer));
        }
        if (set_material_pointer != nullptr &&
            set_material_pointer != set_shared_material_pointer &&
            has_inline_hook_space(tmp_text_class, set_material_pointer)) {
            MSHookFunction(set_material_pointer,
                           reinterpret_cast<void *>(replacement_tmp_set_material),
                           reinterpret_cast<void **>(&g_original_tmp_set_material));
        } else if (set_material_pointer != nullptr &&
                   set_material_pointer != set_shared_material_pointer) {
            LT_LOGW("Skip unsafe adjacent TMP material setter=%p", set_material_pointer);
        }
        LT_LOGI("TMP material hooks shared=%p/%p instance=%p/%p",
                set_shared_material_pointer,
                reinterpret_cast<void *>(g_original_tmp_set_shared_material),
                set_material_pointer,
                reinterpret_cast<void *>(g_original_tmp_set_material));
        void *text_color_class = nullptr;
        for (void *klass = tmp_text_class; klass != nullptr;
             klass = class_get_parent == nullptr ? nullptr : class_get_parent(klass)) {
            if (class_get_method_from_name(klass, "set_color", 1) ==
                tmp_set_text_color_method) {
                text_color_class = klass;
                break;
            }
        }
        void *text_color_pointer = nullptr;
        if (tmp_set_text_color_method != nullptr) {
            memcpy(&text_color_pointer, tmp_set_text_color_method, sizeof(void *));
        }
        if (text_color_pointer != nullptr && text_color_class != nullptr &&
            has_inline_hook_space(text_color_class, text_color_pointer)) {
            MSHookFunction(text_color_pointer,
                           reinterpret_cast<void *>(replacement_tmp_set_text_color),
                           reinterpret_cast<void **>(&g_original_tmp_set_color));
        } else if (text_color_pointer != nullptr) {
            LT_LOGW("Skip unsafe adjacent TMP_Text.set_color pointer=%p",
                    text_color_pointer);
        }
        void *material_set_color_pointer = nullptr;
        if (material_set_color_method != nullptr) {
            memcpy(&material_set_color_pointer, material_set_color_method,
                   sizeof(void *));
        }
        if (material_set_color_pointer != nullptr &&
            has_inline_hook_space(material_class, material_set_color_pointer)) {
            MSHookFunction(material_set_color_pointer,
                           reinterpret_cast<void *>(replacement_material_set_color),
                           reinterpret_cast<void **>(&g_original_material_set_color));
        } else if (material_set_color_pointer != nullptr) {
            LT_LOGW("Skip unsafe adjacent Material.SetColor pointer=%p",
                    material_set_color_pointer);
        }
        void *material_set_float_pointer = nullptr;
        if (material_set_float_method != nullptr) {
            memcpy(&material_set_float_pointer, material_set_float_method,
                   sizeof(void *));
        }
        if (material_set_float_pointer != nullptr &&
            has_inline_hook_space(material_class, material_set_float_pointer)) {
            MSHookFunction(material_set_float_pointer,
                           reinterpret_cast<void *>(replacement_material_set_float),
                           reinterpret_cast<void **>(&g_original_material_set_float));
        } else if (material_set_float_pointer != nullptr) {
            LT_LOGW("Skip unsafe adjacent Material.SetFloat pointer=%p",
                    material_set_float_pointer);
        }
        LT_LOGI("TMP vertex and material mutation hooks color=%p/%p"
                " materialColor=%p/%p materialFloat=%p/%p",
                text_color_pointer,
                reinterpret_cast<void *>(g_original_tmp_set_color),
                material_set_color_pointer,
                reinterpret_cast<void *>(g_original_material_set_color),
                material_set_float_pointer,
                reinterpret_cast<void *>(g_original_material_set_float));
        if (ui_pointer != nullptr && has_inline_hook_space(ui_text_class, ui_pointer)) {
            MSHookFunction(ui_pointer, reinterpret_cast<void *>(replacement_ui_set_text),
                           reinterpret_cast<void **>(&g_original_ui_set_text));
        } else if (ui_pointer != nullptr) {
            LT_LOGW("Skip unsafe adjacent UI.Text.set_text pointer=%p", ui_pointer);
        }
        if (text_mesh_pointer != nullptr &&
            has_inline_hook_space(text_mesh_class, text_mesh_pointer)) {
            MSHookFunction(
                    text_mesh_pointer,
                    reinterpret_cast<void *>(replacement_text_mesh_set_text),
                    reinterpret_cast<void **>(&g_original_text_mesh_set_text));
        } else if (text_mesh_pointer != nullptr) {
            LT_LOGW("Skip unsafe adjacent TextMesh.set_text pointer=%p",
                    text_mesh_pointer);
        }
        if (game_object_set_active_pointer != nullptr &&
            has_inline_hook_space(game_object_class, game_object_set_active_pointer)) {
            MSHookFunction(
                    game_object_set_active_pointer,
                    reinterpret_cast<void *>(replacement_game_object_set_active),
                    reinterpret_cast<void **>(&g_original_game_object_set_active));
        } else if (game_object_set_active_pointer != nullptr) {
            LT_LOGW("Skip unsafe adjacent GameObject.SetActive pointer=%p",
                    game_object_set_active_pointer);
        }
        void *set_text_one_pointer = nullptr;
        if (tmp_set_text_one_method != nullptr) {
            memcpy(&set_text_one_pointer, tmp_set_text_one_method, sizeof(void *));
            MSHookFunction(set_text_one_pointer,
                           reinterpret_cast<void *>(replacement_tmp_set_text_one),
                           reinterpret_cast<void **>(&g_original_tmp_set_text_one));
        }
        void *set_text_string_pointer = nullptr;
        if (tmp_set_text_string_method != nullptr) {
            memcpy(&set_text_string_pointer, tmp_set_text_string_method, sizeof(void *));
            MSHookFunction(set_text_string_pointer,
                           reinterpret_cast<void *>(replacement_tmp_set_text_string),
                           reinterpret_cast<void **>(&g_original_tmp_set_text_string));
        }
        void *set_text_chars_pointer = nullptr;
        if (tmp_set_text_chars_method != nullptr) {
            memcpy(&set_text_chars_pointer, tmp_set_text_chars_method, sizeof(void *));
            if (has_inline_hook_space(tmp_text_class, set_text_chars_pointer)) {
                MSHookFunction(
                        set_text_chars_pointer,
                        reinterpret_cast<void *>(replacement_tmp_set_text_chars),
                        reinterpret_cast<void **>(&g_original_tmp_set_text_chars));
            }
        }
        void *set_text_chars_range_pointer = nullptr;
        if (tmp_set_text_chars_range_method != nullptr) {
            memcpy(&set_text_chars_range_pointer, tmp_set_text_chars_range_method, sizeof(void *));
            if (has_inline_hook_space(tmp_text_class, set_text_chars_range_pointer)) {
                MSHookFunction(
                        set_text_chars_range_pointer,
                        reinterpret_cast<void *>(replacement_tmp_set_text_chars_range),
                        reinterpret_cast<void **>(&g_original_tmp_set_text_chars_range));
            }
        }
        void *set_char_array_pointer = nullptr;
        if (tmp_set_char_array_method != nullptr) {
            memcpy(&set_char_array_pointer, tmp_set_char_array_method, sizeof(void *));
            if (has_inline_hook_space(tmp_text_class, set_char_array_pointer)) {
                MSHookFunction(
                        set_char_array_pointer,
                        reinterpret_cast<void *>(replacement_tmp_set_char_array),
                        reinterpret_cast<void **>(&g_original_tmp_set_char_array));
            }
        }
        void *set_char_array_range_pointer = nullptr;
        if (tmp_set_char_array_range_method != nullptr) {
            memcpy(&set_char_array_range_pointer, tmp_set_char_array_range_method, sizeof(void *));
            if (has_inline_hook_space(tmp_text_class, set_char_array_range_pointer)) {
                MSHookFunction(
                        set_char_array_range_pointer,
                        reinterpret_cast<void *>(replacement_tmp_set_char_array_range),
                        reinterpret_cast<void **>(&g_original_tmp_set_char_array_range));
            }
        }
        if (get_language_pointer != nullptr) {
            MSHookFunction(get_language_pointer,
                           reinterpret_cast<void *>(replacement_get_language),
                           reinterpret_cast<void **>(&g_original_get_language));
        }
        MSHookFunction(get_global_language_pointer,
                       reinterpret_cast<void *>(replacement_global_get_language),
                       reinterpret_cast<void **>(&g_original_global_get_language));
        size_t acquisition_counts[] = {0, 0, 0};
        std::unordered_set<void *> acquisition_pointers;
        bool acquisition_ready = class_get_methods != nullptr && method_get_name != nullptr &&
                method_get_param_count != nullptr && method_get_return_type != nullptr &&
                method_get_param != nullptr &&
                type_get_name != nullptr && il2cpp_free != nullptr &&
                class_get_parent != nullptr && class_get_fields != nullptr &&
                field_get_name != nullptr && field_get_type != nullptr &&
                field_get_value != nullptr && field_set_value != nullptr;
        if (acquisition_ready) {
            for (const AcquisitionTarget &target : acquisition_targets) {
                void *iterator = nullptr;
                const void *method = nullptr;
                while (target.klass != nullptr &&
                       (method = class_get_methods(target.klass, &iterator)) != nullptr) {
                    const char *method_name = method_get_name(method);
                    if (method_name == nullptr ||
                        (strncmp(method_name, "Get", 3) != 0 &&
                         strncmp(method_name, "get_", 4) != 0)) {
                        continue;
                    }
                    char *return_name = type_get_name(method_get_return_type(method));
                    bool returns_string = return_name != nullptr &&
                            strcmp(return_name, "System.String") == 0;
                    if (return_name != nullptr) il2cpp_free(return_name);
                    uint32_t parameter_count = method_get_param_count(method);
                    bool selected_formatter = parameter_count >= 1 && parameter_count <= 2;
                    if (!returns_string || !selected_formatter) continue;
                    std::string battle_hint_table;
                    bool battle_hint_id_method = false;
                    if (battle_hint_table_name(target.name, &battle_hint_table)) {
                        char *first_param_name =
                                type_get_name(method_get_param(method, 0));
                        battle_hint_id_method = first_param_name != nullptr &&
                                strcmp(first_param_name, "System.Int32") == 0;
                        if (first_param_name != nullptr) il2cpp_free(first_param_name);
                    }
                    void *pointer = nullptr;
                    memcpy(&pointer, method, sizeof(pointer));
                    if (pointer == nullptr || acquisition_pointers.count(pointer) != 0 ||
                        acquisition_counts[parameter_count] >= kAcquisitionSlots) {
                        continue;
                    }
                    if (!has_inline_hook_space(target.klass, pointer)) {
                        LT_LOGW("Skip unsafe adjacent text formatter class=%s method=%s pointer=%p",
                                target.name.c_str(), method_name, pointer);
                        continue;
                    }
                    size_t slot = acquisition_counts[parameter_count];
                    if (parameter_count == 0) {
                        MSHookFunction(pointer,
                                reinterpret_cast<void *>(kAcquisition0Replacements[slot]),
                                reinterpret_cast<void **>(&g_acquisition0[slot]));
                        if (g_acquisition0[slot] == nullptr) continue;
                    } else if (parameter_count == 1) {
                        MSHookFunction(pointer,
                                reinterpret_cast<void *>(kAcquisition1Replacements[slot]),
                                reinterpret_cast<void **>(&g_acquisition1[slot]));
                        if (g_acquisition1[slot] == nullptr) continue;
                    } else {
                        MSHookFunction(pointer,
                                reinterpret_cast<void *>(kAcquisition2Replacements[slot]),
                                reinterpret_cast<void **>(&g_acquisition2[slot]));
                        if (g_acquisition2[slot] == nullptr) continue;
                    }
                    if (battle_hint_id_method) {
                        if (parameter_count == 1) {
                            g_acquisition1_battle_hint_tables[slot] = battle_hint_table;
                        } else {
                            g_acquisition2_battle_hint_tables[slot] = battle_hint_table;
                        }
                    }
                    acquisition_pointers.insert(pointer);
                    acquisition_counts[parameter_count]++;
                    LT_LOGI("Text acquisition hook class=%s method=%s argc=%u pointer=%p"
                            " battleHint=%s",
                            target.name.c_str(), method_name, parameter_count, pointer,
                            battle_hint_id_method ? battle_hint_table.c_str() : "-");
                }
            }
        }
        g_hook_installed = g_original_tmp_set_text != nullptr;
        LT_LOGI("TMP translation hook installed=%d setter=%p/%p SetText1=%p/%p"
                " SetText2=%p/%p CharText=%p/%p CharTextRange=%p/%p"
                " CharArray=%p/%p CharArrayRange=%p/%p",
                g_hook_installed, tmp_pointer, reinterpret_cast<void *>(g_original_tmp_set_text),
                set_text_one_pointer, reinterpret_cast<void *>(g_original_tmp_set_text_one),
                set_text_string_pointer, reinterpret_cast<void *>(g_original_tmp_set_text_string),
                set_text_chars_pointer, reinterpret_cast<void *>(g_original_tmp_set_text_chars),
                set_text_chars_range_pointer,
                reinterpret_cast<void *>(g_original_tmp_set_text_chars_range),
                set_char_array_pointer, reinterpret_cast<void *>(g_original_tmp_set_char_array),
                set_char_array_range_pointer,
                reinterpret_cast<void *>(g_original_tmp_set_char_array_range));
        LT_LOGI("UI.Text translation hook setter=%p/%p",
                ui_pointer, reinterpret_cast<void *>(g_original_ui_set_text));
        LT_LOGI("TextMesh diagnostic hook setter=%p/%p",
                text_mesh_pointer,
                reinterpret_cast<void *>(g_original_text_mesh_set_text));
        LT_LOGI("GameObject activation refresh hook setter=%p/%p",
                game_object_set_active_pointer,
                reinterpret_cast<void *>(g_original_game_object_set_active));
        LT_LOGI("Japanese language hooks installed option=%p/%p global=%p/%p",
                get_language_pointer, reinterpret_cast<void *>(g_original_get_language),
                get_global_language_pointer,
                reinterpret_cast<void *>(g_original_global_get_language));
        LT_LOGI("Text acquisition hooks installed argc0=%zu argc1=%zu argc2=%zu ready=%d",
                acquisition_counts[0], acquisition_counts[1], acquisition_counts[2],
                acquisition_ready);
    } else if (!hook_ready) {
        LT_LOGW("TMP translation hook prerequisites missing text=%p setFont=%p createTmp=%p"
                " createDynamic=%p language=%p/%p strings=%p/%p/%p invoke=%p",
                tmp_pointer, tmp_set_font_pointer, create_tmp_font_pointer,
                create_dynamic_font_pointer, get_language_pointer, get_global_language_pointer,
                reinterpret_cast<void *>(string_length),
                reinterpret_cast<void *>(string_chars), reinterpret_cast<void *>(string_new_utf16),
                reinterpret_cast<void *>(runtime_invoke));
    }
    return tmp_method != nullptr && tmp_pointer != nullptr;
}

void *replacement_il2cpp_init(const char *domain_name) {
    void *domain = g_original_il2cpp_init(domain_name);
    MappedElfResolver resolver;
    bool resolved = resolver.open(g_il2cpp_path) && report_il2cpp_resolver(resolver, true);
    pthread_mutex_lock(&g_lock);
    g_resolver_reported = resolved;
    pthread_mutex_unlock(&g_lock);
    LT_LOGI("IL2CPP init boundary domain=%p translationReady=%d path=%s",
            domain, resolved, g_il2cpp_path);
    return domain;
}

void *replacement_unity_dlsym(void *handle, const char *name) {
    void *resolved = g_original_unity_dlsym(handle, name);
    if (name != nullptr && strcmp(name, "il2cpp_init") == 0 && resolved != nullptr) {
        g_original_il2cpp_init = reinterpret_cast<il2cpp_init_fn>(resolved);
        LT_LOGI("Unity requested il2cpp_init original=%p replacement=%p",
                resolved, reinterpret_cast<void *>(replacement_il2cpp_init));
        return reinterpret_cast<void *>(replacement_il2cpp_init);
    }
    return resolved;
}

bool install_global_dlsym_lifecycle_hook() {
    pthread_mutex_lock(&g_lock);
    if (g_probe_started) {
        pthread_mutex_unlock(&g_lock);
        return true;
    }
    pthread_mutex_unlock(&g_lock);
    void *target = dlsym(RTLD_DEFAULT, "dlsym");
    if (target == nullptr) return false;
    MSHookFunction(target, reinterpret_cast<void *>(replacement_unity_dlsym),
                   reinterpret_cast<void **>(&g_original_unity_dlsym));
    if (g_original_unity_dlsym == nullptr) {
        LT_LOGW("Unable to hook dlsym lifecycle target=%p", target);
        return false;
    }
    pthread_mutex_lock(&g_lock);
    g_probe_started = true;
    const char *real_lib_dir = getenv("V_LIMBUS_REAL_LIB_DIR");
    if (real_lib_dir != nullptr) {
        snprintf(g_il2cpp_path, sizeof(g_il2cpp_path), "%s/libil2cpp.so", real_lib_dir);
    }
    pthread_mutex_unlock(&g_lock);
    LT_LOGI("Hooked dlsym lifecycle target=%p trampoline=%p",
            target, reinterpret_cast<void *>(g_original_unity_dlsym));
    return true;
}

int mapping_protection(void *address) {
    FILE *maps = fopen("/proc/self/maps", "r");
    char line[PATH_MAX + 160] = {};
    uintptr_t target = reinterpret_cast<uintptr_t>(address);
    int protection = PROT_READ;
    while (maps != nullptr && fgets(line, sizeof(line), maps) != nullptr) {
        unsigned long start = 0;
        unsigned long end = 0;
        char permissions[5] = {};
        if (sscanf(line, "%lx-%lx %4s", &start, &end, permissions) == 3 &&
            target >= start && target < end) {
            protection = 0;
            if (permissions[0] == 'r') protection |= PROT_READ;
            if (permissions[1] == 'w') protection |= PROT_WRITE;
            if (permissions[2] == 'x') protection |= PROT_EXEC;
            break;
        }
    }
    if (maps != nullptr) fclose(maps);
    return protection;
}

bool install_unity_dlsym_redirect(const char *il2cpp_path) {
    if (il2cpp_path == nullptr) return false;
    pthread_mutex_lock(&g_lock);
    if (g_probe_started) {
        pthread_mutex_unlock(&g_lock);
        return true;
    }
    pthread_mutex_unlock(&g_lock);
    std::string unity_path(il2cpp_path);
    size_t slash = unity_path.rfind('/');
    if (slash == std::string::npos) return false;
    unity_path.replace(slash + 1, std::string::npos, "libunity.so");
    MappedElfResolver resolver;
    if (!resolver.open(unity_path.c_str())) return false;
    void **slot = resolver.resolve_import_slot("dlsym");
    if (slot == nullptr || *slot == nullptr) return false;
    long page_size = sysconf(_SC_PAGESIZE);
    uintptr_t page = reinterpret_cast<uintptr_t>(slot) & ~(static_cast<uintptr_t>(page_size) - 1);
    int original_protection = mapping_protection(slot);
    if (mprotect(reinterpret_cast<void *>(page), static_cast<size_t>(page_size),
                 original_protection | PROT_WRITE) != 0) {
        LT_LOGW("Unable to write Unity dlsym import slot=%p errno=%d", slot, errno);
        return false;
    }
    g_original_unity_dlsym = reinterpret_cast<dlsym_fn>(*slot);
    __atomic_store_n(slot, reinterpret_cast<void *>(replacement_unity_dlsym), __ATOMIC_RELEASE);
    mprotect(reinterpret_cast<void *>(page), static_cast<size_t>(page_size), original_protection);
    pthread_mutex_lock(&g_lock);
    g_probe_started = true;
    snprintf(g_il2cpp_path, sizeof(g_il2cpp_path), "%s", il2cpp_path);
    pthread_mutex_unlock(&g_lock);
    LT_LOGI("Redirected Unity dlsym import slot=%p original=%p replacement=%p",
            slot, reinterpret_cast<void *>(g_original_unity_dlsym),
            reinterpret_cast<void *>(replacement_unity_dlsym));
    return true;
}

}  // namespace

void configure_limbus_translation_runtime(const char *active_index_pointer) {
    if (active_index_pointer == nullptr || active_index_pointer[0] != '/') {
        LT_LOGW("Reject invalid active index pointer");
        return;
    }
    pthread_mutex_lock(&g_lock);
    snprintf(g_active_pointer, sizeof(g_active_pointer), "%s", active_index_pointer);
    g_resolver_reported = false;
    g_probe_started = false;
    g_il2cpp_path[0] = 0;
    g_index.clear();
    g_terms.clear();
    g_fallback_index.clear();
    g_context_index.clear();
    g_battle_hint_index.clear();
    g_package_line_breaks.clear();
    g_term_trie.clear();
    bool index_loaded = load_active_index();
    g_configured = index_loaded &&
            (!g_index.empty() || !g_terms.empty() || !g_fallback_index.empty() ||
             !g_context_index.empty() || !g_battle_hint_index.empty());
    if (!g_configured) {
        LT_LOGI("Pure vanilla mode active (no translation index loaded); skipping dlsym and IL2CPP inline hooks");
    }
    pthread_mutex_unlock(&g_lock);
}

void activate_limbus_translation_lifecycle_hook() {
    pthread_mutex_lock(&g_lock);
    bool active = g_configured &&
            (!g_index.empty() || !g_terms.empty() || !g_fallback_index.empty() ||
             !g_context_index.empty() || !g_battle_hint_index.empty());
    pthread_mutex_unlock(&g_lock);
    if (!active) return;
    install_global_dlsym_lifecycle_hook();
}

void on_limbus_translation_library_loaded(const char *name, void *handle) {
    if (handle != nullptr) probe_limbus_translation_runtime(name);
}

void probe_limbus_translation_runtime(const char *library_path) {
    if (!g_configured || library_path == nullptr || library_path[0] != '/' ||
        strstr(library_path, "libil2cpp.so") == nullptr) return;
    pthread_mutex_lock(&g_lock);
    snprintf(g_il2cpp_path, sizeof(g_il2cpp_path), "%s", library_path);
    pthread_mutex_unlock(&g_lock);
}
