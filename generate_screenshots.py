import os
from PIL import Image, ImageDraw, ImageFont, ImageFilter
import arabic_reshaper
from bidi.algorithm import get_display

def reshape_ar(text):
    return get_display(arabic_reshaper.reshape(text))

def generate_screenshots():
    width = 720
    height = 1280
    
    font_title = ImageFont.truetype(r"C:\Windows\Fonts\segoeuib.ttf", 40)
    font_sub = ImageFont.truetype(r"C:\Windows\Fonts\arialbd.ttf", 26)
    font_text = ImageFont.truetype(r"C:\Windows\Fonts\segoeui.ttf", 20)
    font_small = ImageFont.truetype(r"C:\Windows\Fonts\segoeui.ttf", 16)
    
    # Load Icon
    icon_path = r"S:\SecretVault\app_icon_512.png"
    icon = Image.open(icon_path).convert("RGBA").resize((120, 120), Image.Resampling.LANCZOS)
    
    # ---------------- SCREENSHOT 1: Smart Camera Camouflage ----------------
    s1 = Image.new("RGB", (width, height), (12, 18, 30))
    d1 = ImageDraw.Draw(s1)
    
    # Header Banner
    d1.rectangle([(0, 0), (width, 200)], fill=(16, 26, 44))
    d1.text((width//2, 50), "SMART HD CAMERA", fill=(255, 255, 255), font=font_title, anchor="mt")
    d1.text((width//2, 110), reshape_ar("تمويه كامل على شكل كاميرا تصوير احترافية"), fill=(0, 229, 255), font=font_sub, anchor="mt")
    d1.text((width//2, 155), "Capture photos normally • Secret vault hidden inside", fill=(160, 185, 215), font=font_text, anchor="mt")
    
    # Camera Viewfinder Mockup
    vf_box = [40, 240, width - 40, height - 60]
    d1.rounded_rectangle(vf_box, radius=30, fill=(5, 8, 14), outline=(40, 60, 90), width=3)
    
    # Top bar inside camera
    d1.rounded_rectangle([60, 260, 180, 295], radius=10, fill=(25, 40, 65))
    d1.text((120, 277), "4K • 60 FPS", fill=(0, 229, 255), font=font_small, anchor="mm")
    d1.ellipse([width - 110, 260, width - 75, 295], fill=(30, 45, 70), outline=(0, 200, 255))
    
    # Camera grid lines
    d1.line([(width//3, 330), (width//3, height - 200)], fill=(30, 45, 65, 120), width=1)
    d1.line([(2*width//3, 330), (2*width//3, height - 200)], fill=(30, 45, 65, 120), width=1)
    d1.line([(60, height//2 - 50), (width - 60, height//2 - 50)], fill=(30, 45, 65, 120), width=1)
    d1.line([(60, height//2 + 100), (width - 60, height//2 + 100)], fill=(30, 45, 65, 120), width=1)
    
    # Secret Trigger Gesture visual
    d1.ellipse([width//2 - 110, height//2 - 40, width//2 + 110, height//2 + 180], outline=(0, 229, 255), width=4)
    d1.text((width//2, height//2 + 70), reshape_ar("ارسم دائرة سرية لفتح الخزنة"), fill=(255, 255, 255), font=font_sub, anchor="mm")
    
    # Shutter Button
    d1.ellipse([width//2 - 42, height - 165, width//2 + 42, height - 81], fill=(240, 245, 255), outline=(100, 120, 150), width=4)
    d1.ellipse([width//2 - 34, height - 157, width//2 + 34, height - 89], fill=(255, 255, 255))
    
    s1.save(r"S:\SecretVault\screenshot_1_camera.png", "PNG")
    
    # ---------------- SCREENSHOT 2: Encrypted Vault ----------------
    s2 = Image.new("RGB", (width, height), (12, 18, 30))
    d2 = ImageDraw.Draw(s2)
    
    # Header Banner
    d2.rectangle([(0, 0), (width, 200)], fill=(16, 26, 44))
    d2.text((width//2, 50), "ENCRYPTED VAULT", fill=(255, 255, 255), font=font_title, anchor="mt")
    d2.text((width//2, 110), reshape_ar("خزنة سرية فائقة الأمان للصور والفيديوهات"), fill=(0, 229, 255), font=font_sub, anchor="mt")
    d2.text((width//2, 155), "Military AES-256 Encryption • Auto Shred & Delete", fill=(160, 185, 215), font=font_text, anchor="mt")
    
    # Vault UI Cards Mockup
    cards = [
        ("الصور السرية (Photos)", "128 صورة مشفرة", (0, 180, 255)),
        ("الفيديوهات الخاصة (Videos)", "42 فيديو محمي", (0, 230, 180)),
        ("المستندات والملفات (Files)", "16 ملف حساس", (255, 180, 0)),
        ("سجل المتطفلين (Intruders)", "تم التقاط 2 متطفل", (255, 75, 100))
    ]
    card_y = 250
    for title, count, color in cards:
        d2.rounded_rectangle([50, card_y, width - 50, card_y + 115], radius=20, fill=(20, 32, 50), outline=color, width=2)
        d2.ellipse([75, card_y + 25, 140, card_y + 90], fill=(28, 46, 72))
        d2.ellipse([95, card_y + 45, 120, card_y + 70], fill=color)
        d2.text((165, card_y + 35), reshape_ar(title), fill=(255, 255, 255), font=font_sub)
        d2.text((165, card_y + 75), reshape_ar(count), fill=(160, 190, 220), font=font_text)
        card_y += 140
        
    # Bottom security badge
    d2.rounded_rectangle([70, height - 160, width - 70, height - 70], radius=18, fill=(15, 45, 30), outline=(0, 230, 120), width=1)
    d2.text((width//2, height - 128), reshape_ar("تشفير محلي عسكري 100% بدون إنترنت"), fill=(0, 255, 140), font=font_sub, anchor="mm")
    d2.text((width//2, height - 95), "PBKDF2 SHA-256 Key Derivation & AES-256-GCM", fill=(180, 230, 200), font=font_small, anchor="mm")
    
    s2.save(r"S:\SecretVault\screenshot_2_vault.png", "PNG")

    # ---------------- SCREENSHOT 3: Security & Intruder Selfie ----------------
    s3 = Image.new("RGB", (width, height), (12, 18, 30))
    d3 = ImageDraw.Draw(s3)
    
    # Header Banner
    d3.rectangle([(0, 0), (width, 200)], fill=(16, 26, 44))
    d3.text((width//2, 50), "MAXIMUM SECURITY", fill=(255, 255, 255), font=font_title, anchor="mt")
    d3.text((width//2, 110), reshape_ar("التقاط صورة المتطفل تلقائياً عند الخطأ"), fill=(0, 229, 255), font=font_sub, anchor="mt")
    d3.text((width//2, 155), "Intruder Selfie Capture • Decoy Fake PIN Support", fill=(160, 185, 215), font=font_text, anchor="mt")
    
    # Center Keypad & Security Frame
    d3.rounded_rectangle([50, 240, width - 50, height - 80], radius=28, fill=(18, 28, 45), outline=(45, 70, 110), width=2)
    
    # App Icon centered
    s3.paste(icon, (width//2 - 60, 270), icon)
    d3.text((width//2, 420), reshape_ar("أدخل رمز الحماية السري"), fill=(240, 245, 255), font=font_sub, anchor="mm")
    
    # PIN Dots
    dot_x = width//2 - 60
    for i in range(4):
        d3.ellipse([dot_x, 460, dot_x + 20, 480], fill=(0, 229, 255))
        dot_x += 40
        
    # Keypad Grid
    keys = [
        ["1", "2", "3"],
        ["4", "5", "6"],
        ["7", "8", "9"],
        ["*", "0", "#"]
    ]
    key_y = 520
    for row in keys:
        key_x = width//2 - 160
        for k in row:
            d3.ellipse([key_x, key_y, key_x + 85, key_y + 85], fill=(26, 40, 62), outline=(45, 70, 105), width=1)
            d3.text((key_x + 42, key_y + 42), k, fill=(255, 255, 255), font=font_title, anchor="mm")
            key_x += 120
        key_y += 105
        
    # Feature Pill
    d3.rounded_rectangle([75, height - 170, width - 75, height - 110], radius=15, fill=(35, 20, 30), outline=(255, 80, 100), width=1)
    d3.text((width//2, height - 140), reshape_ar("دعم الرمز الوهمي Decoy PIN لحالات الإجبار"), fill=(255, 140, 150), font=font_sub, anchor="mm")
    
    s3.save(r"S:\SecretVault\screenshot_3_security.png", "PNG")
    print("Generated 3 screenshots successfully!")

if __name__ == "__main__":
    generate_screenshots()
