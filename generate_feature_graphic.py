import os
from PIL import Image, ImageDraw, ImageFont, ImageFilter
import arabic_reshaper
from bidi.algorithm import get_display

def create_feature_graphic():
    width = 1024
    height = 500
    
    # 24-bit RGB (no alpha - exactly as required by APKPure and Google Play)
    img = Image.new("RGB", (width, height), color=(10, 16, 28))
    draw = ImageDraw.Draw(img)
    
    # Create smooth dark modern gradient
    for y in range(height):
        ratio = y / height
        r = int(10 * (1 - ratio) + 18 * ratio)
        g = int(16 * (1 - ratio) + 28 * ratio)
        b = int(28 * (1 - ratio) + 52 * ratio)
        draw.line([(0, y), (width, y)], fill=(r, g, b))
        
    # Draw soft glow circles
    accent_layer = Image.new("RGB", (width, height), color=(0, 0, 0))
    accent_draw = ImageDraw.Draw(accent_layer)
    accent_draw.ellipse([620, -60, 1080, 400], fill=(0, 130, 240))
    accent_draw.ellipse([-40, 160, 420, 620], fill=(0, 80, 170))
    accent_layer = accent_layer.filter(ImageFilter.GaussianBlur(radius=90))
    
    img = Image.blend(img, accent_layer, alpha=0.32)
    draw = ImageDraw.Draw(img)
    
    # Load and place app icon
    icon_path = r"S:\SecretVault\app_icon_512.png"
    if os.path.exists(icon_path):
        icon = Image.open(icon_path).convert("RGBA")
        icon = icon.resize((310, 310), Image.Resampling.LANCZOS)
        
        # Add shadow behind icon
        shadow = Image.new("RGBA", (330, 330), (0, 0, 0, 0))
        shadow_draw = ImageDraw.Draw(shadow)
        shadow_draw.rounded_rectangle([10, 10, 320, 320], radius=55, fill=(0, 0, 0, 210))
        shadow = shadow.filter(ImageFilter.GaussianBlur(14))
        
        icon_pos_x = 75
        icon_pos_y = (height - 310) // 2
        
        img.paste(shadow, (icon_pos_x - 10, icon_pos_y - 10), shadow)
        img.paste(icon, (icon_pos_x, icon_pos_y), icon)

    # Fonts
    font_bold_title = ImageFont.truetype(r"C:\Windows\Fonts\segoeuib.ttf", 52)
    font_arabic = ImageFont.truetype(r"C:\Windows\Fonts\arialbd.ttf", 36)
    font_sub = ImageFont.truetype(r"C:\Windows\Fonts\segoeui.ttf", 22)
    font_badge = ImageFont.truetype(r"C:\Windows\Fonts\segoeuib.ttf", 18)

    text_x = 430
    
    # Main Title
    draw.text((text_x, 90), "CAMERA VAULT", fill=(255, 255, 255), font=font_bold_title)
    
    # Arabic Title correctly reshaped
    ar_text = "خزنة الكاميرا السرية"
    reshaped_ar = arabic_reshaper.reshape(ar_text)
    bidi_ar = get_display(reshaped_ar)
    draw.text((text_x, 160), bidi_ar, fill=(0, 229, 255), font=font_arabic)
    
    # Subtitle features
    draw.text((text_x, 225), "Military AES-256-GCM Encryption", fill=(195, 215, 240), font=font_sub)
    draw.text((text_x, 265), "Smart HD Camera Camouflage • 100% Private", fill=(145, 175, 205), font=font_sub)
    
    # Badges (without emoji symbols to avoid unrendered font squares)
    badges = [
        "AES-256 Encrypted",
        "HD Camouflage",
        "100% Offline"
    ]
    badge_y = 330
    cur_x = text_x
    for b in badges:
        bbox = draw.textbbox((0, 0), b, font=font_badge)
        bw = bbox[2] - bbox[0] + 28
        bh = bbox[3] - bbox[1] + 16
        
        draw.rounded_rectangle([cur_x, badge_y, cur_x + bw, badge_y + bh], radius=10, fill=(18, 34, 58), outline=(0, 185, 255), width=1)
        draw.text((cur_x + 14, badge_y + 8), b, fill=(225, 245, 255), font=font_badge)
        cur_x += bw + 15

    output_path = r"S:\SecretVault\feature_graphic_1024x500.png"
    img.save(output_path, "PNG")
    print(f"Generated perfect banner: {output_path}")

if __name__ == "__main__":
    create_feature_graphic()
