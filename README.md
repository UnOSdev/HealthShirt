Bu YTU Gömülü Sistemler Laboratuvarı kapsamında geliştirilen giyilebilir bir sağlık izleme sistemi projesidir. 
Projede, gerçek zamanlı olarak kalp atış hızı ve vücut sıcaklığını ölçebilen, bu verileri kablosuz iletişimle mobil uygulamaya aktaran bir sistem tasarlanmış ve uygulanmıştır.


Amaç: Kronik hastalar, yaşlılar ve sporcular gibi kullanıcılar için hayati parametrelerin sürekli izlenmesi ve olası risklerin erken tespit edilmesini sağlayacak, giyilebilir bir sağlık monitörü geliştirmek.
Kullanılan Donanım:
    -Mikrodenetleyici: Arduino UNO R3
    -Kalp Sensörü: MAX30102 (Nabız ve oksijen seviyesi ölçümü)
    -Dokunmatik Sensör: TTP223B (Sistem açma/kapama için)
    -Bluetooth Modülü: HC-05 (Veri iletimi için)
    -OLED Ekran (Kıyafete entegre edilmiştir)


Arduino Yazılımı: Sensör verilerini okuyor, işliyor ve Bluetooth ile iletiyor.
Android Uygulaması: Bluetooth üzerinden gelen verileri gerçek zamanlı olarak görselleştiriyor, grafiklerle sunuyor ve kullanıcıyı bilgilendiriyor.
