#!/usr/bin/env python3
# Copyright 2026 Christian Kierdorf
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
"""OCR samples in many languages for SampleSetEvalTest (ported from Sheet):
a short notice per language, rendered once clean (a scan, serif where the
script has one) and once as a phone photo (slight keystone and turn, light
falling off, blur, noise, JPEG), each with its ground truth. MakeACopy's
sets have one clean page per language and none for Korean, Vietnamese,
Ukrainian, Bulgarian or Traditional Chinese photographed.

    scripts/synth_samples.py <font dir> app/src/androidTest/assets/eval/samples

Needs pillow with raqm (Arabic, Devanagari and Thai are shaped) and
fonttools; characters a font lacks (Noto Sans Thai has no digits) come
from NotoSans. The fonts
are Noto (SIL Open Font License): NotoSans, NotoSerif, NotoNaskhArabic,
NotoSansDevanagari, NotoSansThai (github.com/notofonts) and NotoSans{SC,TC,
JP,KR} 2.004 (github.com/notofonts/noto-cjk, Sans/SubsetOTF). The images
are deterministic for given fonts and pillow version.
"""

import random
import sys
from pathlib import Path

from fontTools.ttLib import TTFont
from PIL import Image, ImageDraw, ImageFilter, ImageFont

# code (as the eval maps it to a model): font, serif font or None, right to left, lines
TEXTS = {
    "eng": ("NotoSans-Regular.ttf", "NotoSerif-Regular.ttf", False, [
        "Notice to all tenants",
        "The water will be turned off on Tuesday, 4 March,",
        "from 8:30 to 12:00 for repairs in the basement.",
        "Please fill a few bottles the evening before.",
        "Parcels can be left at flat 2B until 6 pm.",
        "Questions: caretaker, phone 0171 554 2390.",
        "Thank you for your patience.",
    ]),
    "deu": ("NotoSans-Regular.ttf", "NotoSerif-Regular.ttf", False, [
        "Mitteilung an die Mieter",
        "Am Dienstag, dem 4. März, wird das Wasser",
        "von 8:30 bis 12:00 Uhr wegen Arbeiten abgestellt.",
        "Bitte füllen Sie am Vorabend einige Flaschen ab.",
        "Pakete nimmt Frau Müller in Wohnung 2B an.",
        "Rückfragen bitte an den Hausmeister, Tel. 0171 554 2390.",
        "Vielen Dank für Ihr Verständnis und schöne Grüße.",
    ]),
    "fra": ("NotoSans-Regular.ttf", "NotoSerif-Regular.ttf", False, [
        "Avis aux locataires",
        "Le mardi 4 mars, l'eau sera coupée de 8 h 30 à 12 h",
        "en raison de travaux dans le sous-sol de l'immeuble.",
        "Veuillez prévoir quelques bouteilles la veille.",
        "Les colis peuvent être déposés chez Mme Lefèvre.",
        "Pour toute question, appelez le gardien au 06 12 34 56 78.",
        "Merci de votre compréhension et bonne journée.",
    ]),
    "spa": ("NotoSans-Regular.ttf", "NotoSerif-Regular.ttf", False, [
        "Aviso a los vecinos",
        "El martes 4 de marzo se cortará el agua",
        "de 8:30 a 12:00 por reparaciones en el sótano.",
        "Les rogamos que llenen algunas botellas la víspera.",
        "Los paquetes se pueden dejar en el piso 2.º B.",
        "Para dudas, llamen al conserje: 612 345 678.",
        "Gracias por su comprensión. ¡Buen día!",
    ]),
    "pol": ("NotoSans-Regular.ttf", "NotoSerif-Regular.ttf", False, [
        "Ogłoszenie dla mieszkańców",
        "We wtorek 4 marca woda zostanie wyłączona",
        "od 8:30 do 12:00 z powodu prac w piwnicy.",
        "Prosimy o napełnienie kilku butelek dzień wcześniej.",
        "Paczki można zostawić u pani Żółkiewskiej.",
        "Pytania proszę kierować do dozorcy: 601 234 567.",
        "Dziękujemy za wyrozumiałość i życzliwość.",
    ]),
    "ces": ("NotoSans-Regular.ttf", "NotoSerif-Regular.ttf", False, [
        "Oznámení nájemníkům",
        "V úterý 4. března bude voda vypnuta",
        "od 8:30 do 12:00 kvůli opravám ve sklepě.",
        "Prosíme, natočte si večer předem několik lahví.",
        "Balíky můžete nechat u paní Dvořákové v bytě 2B.",
        "Dotazy směřujte na správce, tel. 777 123 456.",
        "Děkujeme za pochopení a přejeme hezký den.",
    ]),
    "slk": ("NotoSans-Regular.ttf", "NotoSerif-Regular.ttf", False, [
        "Oznam pre nájomníkov",
        "V utorok 4. marca bude voda vypnutá",
        "od 8:30 do 12:00 pre opravy v pivnici.",
        "Prosíme, natočte si deň vopred niekoľko fliaš.",
        "Kŕdeľ ďatľov učí koňa žrať kôru v húštine.",
        "Otázky smerujte na správcu, tel. 0905 123 456.",
        "Ďakujeme za pochopenie a prajeme pekný deň.",
    ]),
    "hun": ("NotoSans-Regular.ttf", "NotoSerif-Regular.ttf", False, [
        "Értesítés a lakóknak",
        "Március 4-én, kedden 8:30 és 12:00 között",
        "szünetel a vízszolgáltatás a pincében végzett munkák miatt.",
        "Kérjük, előző este töltsenek meg néhány üveget.",
        "A csomagokat Szőke Ödönné veszi át a 2B lakásban.",
        "Kérdés esetén hívja a gondnokot: 06 30 123 4567.",
        "Köszönjük a türelmüket és megértésüket.",
    ]),
    "ron": ("NotoSans-Regular.ttf", "NotoSerif-Regular.ttf", False, [
        "Anunț pentru locatari",
        "Marți, 4 martie, apa va fi oprită",
        "între orele 8:30 și 12:00 pentru lucrări la subsol.",
        "Vă rugăm să umpleți câteva sticle în seara dinainte.",
        "Coletele pot fi lăsate la doamna Ștefănescu, ap. 2B.",
        "Întrebări: administratorul, telefon 0722 123 456.",
        "Vă mulțumim pentru înțelegere.",
    ]),
    "tur": ("NotoSans-Regular.ttf", "NotoSerif-Regular.ttf", False, [
        "Kiracılara duyuru",
        "4 Mart Salı günü saat 8.30 ile 12.00 arasında",
        "bodrumdaki onarım nedeniyle sular kesilecektir.",
        "Lütfen bir gün önceden birkaç şişe su doldurun.",
        "Kargolar 2B numaralı dairede Ayşe Hanım'a bırakılabilir.",
        "Sorularınız için yönetici: 0532 123 45 67.",
        "Anlayışınız için teşekkür ederiz.",
    ]),
    "vie": ("NotoSans-Regular.ttf", "NotoSerif-Regular.ttf", False, [
        "Thông báo gửi các hộ dân",
        "Thứ Ba ngày 4 tháng 3, nước sẽ bị cắt",
        "từ 8 giờ 30 đến 12 giờ để sửa chữa tầng hầm.",
        "Xin quý vị chuẩn bị sẵn vài chai nước từ tối hôm trước.",
        "Bưu kiện có thể gửi tại căn hộ 2B.",
        "Mọi thắc mắc xin gọi người quản lý: 0912 345 678.",
        "Xin cảm ơn sự thông cảm của quý vị.",
    ]),
    "rus": ("NotoSans-Regular.ttf", "NotoSerif-Regular.ttf", False, [
        "Объявление для жильцов",
        "Во вторник, 4 марта, с 8:30 до 12:00",
        "будет отключена вода из-за ремонта в подвале.",
        "Пожалуйста, наберите воду накануне вечером.",
        "Посылки можно оставить в квартире 2Б.",
        "Вопросы: управляющий, тел. 8 912 345-67-89.",
        "Спасибо за понимание.",
    ]),
    "ukr": ("NotoSans-Regular.ttf", "NotoSerif-Regular.ttf", False, [
        "Оголошення для мешканців",
        "У вівторок, 4 березня, з 8:30 до 12:00",
        "буде вимкнено воду через ремонт у підвалі.",
        "Будь ласка, наберіть воду напередодні ввечері.",
        "Посилки можна залишити в квартирі 2Б у пані Їжакевич.",
        "Питання: управитель, тел. 067 123 45 67.",
        "Дякуємо за розуміння.",
    ]),
    "bul": ("NotoSans-Regular.ttf", "NotoSerif-Regular.ttf", False, [
        "Съобщение за живущите",
        "Във вторник, 4 март, от 8:30 до 12:00 ч.",
        "водата ще бъде спряна заради ремонт в мазето.",
        "Моля, напълнете няколко бутилки предишната вечер.",
        "Пратки може да оставите в апартамент 2Б.",
        "Въпроси: домоуправителят, тел. 0888 123 456.",
        "Благодарим за разбирането.",
    ]),
    "el": ("NotoSans-Regular.ttf", "NotoSerif-Regular.ttf", False, [
        "Ανακοίνωση προς τους ενοίκους",
        "Την Τρίτη 4 Μαρτίου, από τις 8:30 έως τις 12:00,",
        "θα διακοπεί η υδροδότηση λόγω εργασιών στο υπόγειο.",
        "Παρακαλούμε γεμίστε μερικά μπουκάλια το προηγούμενο βράδυ.",
        "Τα δέματα μπορούν να αφεθούν στο διαμέρισμα 2Β.",
        "Ερωτήσεις: διαχειριστής, τηλ. 694 123 4567.",
        "Ευχαριστούμε για την κατανόηση.",
    ]),
    "ara": ("NotoNaskhArabic-Regular.ttf", None, True, [
        "إعلان إلى السكان",
        "سيتم قطع المياه يوم الثلاثاء 4 مارس",
        "من الساعة 8:30 حتى 12:00 بسبب أعمال في القبو.",
        "يرجى تعبئة بعض الزجاجات في الليلة السابقة.",
        "يمكن ترك الطرود في الشقة 2ب.",
        "للاستفسار يرجى الاتصال بالحارس على 0501234567.",
        "شكرا لتفهمكم.",
    ]),
    "fas": ("NotoNaskhArabic-Regular.ttf", None, True, [
        "اطلاعیه برای ساکنان",
        "روز سه‌شنبه ۴ مارس آب ساختمان",
        "از ساعت ۸:۳۰ تا ۱۲:۰۰ به دلیل تعمیرات قطع می‌شود.",
        "لطفا شب قبل چند بطری آب پر کنید.",
        "بسته‌ها را می‌توانید در واحد ۲ب بگذارید.",
        "برای پرسش با سرایدار تماس بگیرید.",
        "از همکاری شما سپاسگزاریم.",
    ]),
    "hin": ("NotoSansDevanagari-Regular.ttf", None, False, [
        "निवासियों के लिए सूचना",
        "मंगलवार 4 मार्च को सुबह 8:30 से 12:00 बजे तक",
        "तहखाने में मरम्मत के कारण पानी बंद रहेगा।",
        "कृपया एक दिन पहले कुछ बोतलें भर लें।",
        "पार्सल फ्लैट 2बी में छोड़े जा सकते हैं।",
        "प्रश्नों के लिए चौकीदार से संपर्क करें: 98765 43210",
        "आपके सहयोग के लिए धन्यवाद।",
    ]),
    "tha": ("NotoSansThai-Regular.ttf", None, False, [
        "ประกาศถึงผู้พักอาศัย",
        "วันอังคารที่ 4 มีนาคม จะปิดน้ำประปา",
        "ตั้งแต่เวลา 8.30 ถึง 12.00 น. เพื่อซ่อมแซมชั้นใต้ดิน",
        "กรุณาเตรียมน้ำใส่ขวดไว้ล่วงหน้าในคืนก่อนหน้า",
        "สามารถฝากพัสดุไว้ที่ห้อง 2B ได้",
        "สอบถามเพิ่มเติมโทร 081 234 5678",
        "ขอบคุณสำหรับความเข้าใจ",
    ]),
    "chi_sim": ("NotoSansSC-Regular.otf", None, False, [
        "住户通知",
        "3月4日星期二上午8:30至12:00",
        "因地下室维修将暂停供水。",
        "请大家提前一晚准备一些饮用水。",
        "快递可以暂存在2B室。",
        "如有疑问请联系物业，电话13812345678。",
        "感谢您的理解与配合。",
    ]),
    "chi_tra": ("NotoSansTC-Regular.otf", None, False, [
        "住戶通知",
        "3月4日星期二上午8:30至12:00",
        "因地下室維修將暫停供水。",
        "請大家提前一晚準備一些飲用水。",
        "包裹可以暫放在2B室。",
        "如有疑問請聯繫管理處，電話0912345678。",
        "感謝您的理解與配合。",
    ]),
    "jpn": ("NotoSansJP-Regular.otf", None, False, [
        "居住者の皆様へ",
        "3月4日（火）8時30分から12時まで",
        "地下の工事のため断水いたします。",
        "前日の夜に飲み水をご用意ください。",
        "お荷物は2B号室でお預かりします。",
        "ご不明な点は管理人までお電話ください。",
        "ご理解とご協力をお願いいたします。",
    ]),
    "kor": ("NotoSansKR-Regular.otf", None, False, [
        "입주민 여러분께 알립니다",
        "3월 4일 화요일 오전 8시 30분부터 12시까지",
        "지하실 공사로 인해 단수됩니다.",
        "전날 저녁에 물을 미리 받아 두시기 바랍니다.",
        "택배는 2B호에 맡기실 수 있습니다.",
        "문의 사항은 관리사무소로 연락해 주십시오.",
        "협조해 주셔서 감사합니다.",
    ]),
}
W, H = 1654, 2339  # A4 at 200 dpi


def runs(line: str, has) -> list[tuple[str, bool]]:
    """[line] cut where it changes between characters the font has (spaces go with either) and others."""
    out: list[tuple[str, bool]] = []
    for ch in line:
        own = has(ch) or (ch.isspace() and bool(out) and out[-1][1])
        if out and (out[-1][1] == own or ch.isspace()):
            out[-1] = (out[-1][0] + ch, out[-1][1])
        else:
            out.append((ch, own))
    return out


def page(fonts: Path, font: str, rtl: bool, lines: list[str], size: int) -> Image.Image:
    img = Image.new("RGB", (W, H), "white")
    d = ImageDraw.Draw(img)
    cmap = TTFont(str(fonts / font), fontNumber=0).getBestCmap()
    direction = "rtl" if rtl else "ltr"
    y = 200
    for k, line in enumerate(lines):
        px = int(size * 1.5) if k == 0 else size
        own = ImageFont.truetype(str(fonts / font), px)
        other = ImageFont.truetype(str(fonts / "NotoSans-Regular.ttf"), px)
        if rtl:
            width = d.textlength(line, font=own, direction=direction)
            d.text((W - 160 - width, y), line, font=own, fill=(15, 15, 15), direction=direction)
        else:
            x = 160
            for text, mine in runs(line, lambda c: ord(c) in cmap):
                f = own if mine else other
                d.text((x, y), text, font=f, fill=(15, 15, 15))
                x += d.textlength(text, font=f)
        y += int(px * 2.1) if k == 0 else int(size * 1.9)
    return img


def photo(img: Image.Image, rnd: random.Random) -> Image.Image:
    # A page cut out of a photo is never quite square: a little keystone and turn remain.
    k = 0.015
    quad = [
        rnd.uniform(-k, k) * W, rnd.uniform(-k, k) * H,
        rnd.uniform(-k, k) * W, H + rnd.uniform(-k, k) * H,
        W + rnd.uniform(-k, k) * W, H + rnd.uniform(-k, k) * H,
        W + rnd.uniform(-k, k) * W, rnd.uniform(-k, k) * H,
    ]
    warped = img.transform((W, H), Image.QUAD, quad, resample=Image.BICUBIC, fillcolor=(225, 220, 210))
    turned = warped.rotate(rnd.uniform(-1.5, 1.5), resample=Image.BICUBIC, fillcolor=(225, 220, 210))
    # Light from a lamp off one corner.
    cx, cy = rnd.choice([(0, 0), (W, 0), (0, H), (W, H)])
    rings = Image.radial_gradient("L").resize((2 * W, 2 * H))
    shade = rings.crop((W - cx, H - cy, 2 * W - cx, 2 * H - cy)).point(lambda v: 255 - v // 3)
    lit = Image.composite(turned, Image.new("RGB", (W, H), (140, 130, 115)), shade)
    blurred = lit.filter(ImageFilter.GaussianBlur(rnd.uniform(0.9, 1.4)))
    noise = Image.effect_noise((W, H), 18).convert("RGB")
    return Image.blend(blurred, noise, 0.06)


def main() -> None:
    fonts, out = Path(sys.argv[1]), Path(sys.argv[2])
    out.mkdir(parents=True, exist_ok=True)
    for code, (sans, serif, rtl, lines) in TEXTS.items():
        rnd = random.Random(code)
        truth = "\n".join(lines) + "\n"
        clean = page(fonts, serif or sans, rtl, lines, 40)
        clean.save(out / f"language_{code}_scan.png")
        photo(page(fonts, sans, rtl, lines, 36), rnd).save(out / f"language_{code}_photo.jpg", quality=80)
        for kind in ("scan", "photo"):
            (out / f"language_{code}_{kind}.gt.txt").write_text(truth, encoding="utf-8")


if __name__ == "__main__":
    main()
