#!/bin/zsh
clear
script_dir="${0:A:h}"
cd "$script_dir/.." || exit 1
python3 tools/mac_ble_throttle_discovery.py
status=$?
echo
if [ "$status" -eq 0 ]; then
  echo "İşlem tamamlandı. Sonucu Codex'e 'bitti' yazarak analiz ettirebilirsin."
elif [ "$status" -eq 2 ]; then
  echo "Ham CAN kapalı görünüyor. Yeniden açıp menüden 2'yi seç."
else
  echo "İşlem tamamlanamadı. Yukarıdaki hata metnini Codex'e gönder."
fi
echo "Pencereyi kapatmak için Enter'a basın."
read
