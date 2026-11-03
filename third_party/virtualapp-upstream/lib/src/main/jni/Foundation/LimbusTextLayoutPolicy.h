#pragma once

#include <cmath>
#include <string>

namespace limbus_text_layout {

// 中文材质独立维护描边参数，不继承日文材质的图集、字重或膨胀值。
constexpr float kChineseOutlineWidth = 0.0f;
// TMP 的描边会向内覆盖字面；等宽膨胀抵消这部分覆盖，将黑边移到原轮廓外。
constexpr float kChineseFaceDilate = 0.0f;
// 战斗技能标题使用 Regular 字形并施加适度正膨胀（约 Medium/SemiBold 字重），
// 避免纯 Regular 过细发虚，同时防止 Bold + 合成粗体造成多笔画字糊连。
constexpr float kChineseBattleSkillBannerFaceDilate = 0.14f;
// 战斗状态文字必须继承源材质的描边开关、颜色和宽度，不能按状态名称猜测配色。
// 中文 SDF 图集密度不同，只对源描边宽度做统一缩放；源材质没有描边时保持关闭。
constexpr float kChineseBattleWinRateOutlineScale = 0.45f;
constexpr float kChineseBattleWinRateMinOutlineWidth = 0.09f;
constexpr float kChineseBattleWinRateMaxOutlineWidth = 0.14f;
constexpr float kChineseBattleWinRateDilateRatio = 0.55f;
// PC 版中文使用白字下方的窄黑托底。Underlay 不再扩边或向右偏移，
// 只把同尺寸的黑色字形轻微下移；否则小字号字腔仍会被四周黑边填暗。
constexpr float kChineseUnderlayDilate = 0.0f;
constexpr float kChineseUnderlayOffsetX = 0.0f;
constexpr float kChineseUnderlayOffsetY = -0.035f;
constexpr float kChineseUnderlayAlpha = 0.9f;
// Japanese controls commonly use offsets around 0.3. Applying that value
// unchanged to the denser CJK SDF atlas makes the underlay look like a second
// glyph. Scale it into the same pixel range as the game's Japanese small text.
constexpr float kChineseUnderlaySourceScale = 1.0f;
constexpr float kChineseUnderlayMaximumOffset = 0.35f;

inline float cjk_underlay_offset(float source) {
    float scaled = source * kChineseUnderlaySourceScale;
    return std::fmax(-kChineseUnderlayMaximumOffset,
                     std::fmin(kChineseUnderlayMaximumOffset, scaled));
}

inline float cjk_battle_winrate_outline_width(float source_outline_width) {
    if (!std::isfinite(source_outline_width) || source_outline_width <= 0.001f) {
        return 0.0f;
    }
    float scaled = source_outline_width * kChineseBattleWinRateOutlineScale;
    return std::fmax(kChineseBattleWinRateMinOutlineWidth,
                     std::fmin(kChineseBattleWinRateMaxOutlineWidth, scaled));
}

inline float cjk_battle_winrate_face_dilate(float cjk_outline_width) {
    if (!std::isfinite(cjk_outline_width) || cjk_outline_width <= 0.001f) {
        return kChineseFaceDilate;
    }
    return cjk_outline_width * kChineseBattleWinRateDilateRatio;
}

// TMP's current fontSize can already be the result of a previous auto-size pass.
inline float configured_font_size(float rendered_size, bool auto_sizing,
                                  bool has_maximum, float maximum) {
    return auto_sizing && has_maximum && std::isfinite(maximum) && maximum > 0.0f
            ? maximum : rendered_size;
}

inline bool has_explicit_break(const std::string &text) {
    if (text.find_first_of("\r\n") != std::string::npos) return true;
    for (size_t start = text.find('<'); start != std::string::npos;
         start = text.find('<', start + 1)) {
        size_t end = text.find('>', start + 1);
        if (end == std::string::npos) break;
        std::string tag = text.substr(start + 1, end - start - 1);
        if (tag == "br" || tag == "br/" || tag == "br /" ||
            tag == "BR" || tag == "BR/" || tag == "BR /") return true;
        start = end;
    }
    return false;
}

inline bool is_single_line(bool explicit_break, bool rect_valid, float height,
                           float font_size, bool has_wrapping, bool wrapping) {
    if (explicit_break) return false;
    // Use the original control's layout, never a character-count threshold.
    return (has_wrapping && !wrapping) ||
            (rect_valid && font_size > 0.0f && height <= font_size * 1.8f);
}

inline float multiline_spacing(float original, float font_size,
                               bool explicit_line_height) {
    return explicit_line_height ? original : std::fmax(original, font_size * 0.24f);
}

}  // namespace limbus_text_layout
