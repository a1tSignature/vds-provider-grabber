# source-workbench — как писать `catalog-seed/sources/*.json`

Инструменты для пакетного написания декларативных источников краулера для кандидатов из
`catalog-seed/catalog-dump.sql`. Конфиги проверяются локально, на сохранённых страницах, тем же
кодом, что и в краулере: формат, `DeclarativeAdapter`, `SnapshotValidator`.

Kotlin-часть — `src/test/kotlin/org/a1tsign/vds/crawler/ScratchSourceProbeTest.kt`. Она запускается
только с переменной `PROBE_DIR`, поэтому обычный `./gradlew test` её пропускает.

## Процесс

```bash
W=/tmp/work            # любой рабочий каталог вне репозитория
B=scripts/source-workbench
RUN='./gradlew test --tests org.a1tsign.vds.crawler.ScratchSourceProbeTest'

# 1. Кандидаты с успешным обогащением, у которых ещё нет файла источника
python3 -I $B/candidates.py catalog-seed/catalog-dump.sql catalog-seed/sources $W

# 2. Страницы так, как их видит краулер (без JS) -> $W/pages, список годных -> $W/auto.txt
$B/fetch.sh $W

# 3. Автопоиск карточек тарифов -> $W/auto.out (селектор группы + HTML первой карточки)
PROBE_DIR=$W $RUN.auto

# 4. Точечные пробы -> $W/probe.out. В $W/probe.txt строки `slug|css-селектор`
#    или `slug|@текст` (путь предков элементов, чей собственный текст содержит «текст»).
#    PROBE_N=20 — сколько совпадений печатать (без HTML), PROBE_HTML=1 — вернуть HTML.
PROBE_DIR=$W $RUN.probe

# 5. Спеки -> JSON (формат спека — в шапке gen.py, живой пример — examples/example-spec.py)
python3 -I $B/gen.py $W/out $W/providers.tsv $W/rest.tsv my-spec.py

# 6. Проверка тремя гейтами краулера -> $W/check.out, затем поиск правдоподобных, но неверных чисел
PROBE_DIR=$W SRC_SUBDIR=out VALIDATE_OUT=check.out $RUN.validate
python3 -I $B/sanity.py $W/check.out

# 7. robots.txt для каждой страницы
for f in $W/out/*.json; do
  read id base page < <(python3 -I -c "import json,sys;c=json.load(open(sys.argv[1]));print(c['id'],c['baseUrl'],c['pagePath'])" "$f")
  curl -sL -m 15 -A vds-provider-grabber/0.1 -o "$W/robots-$id.txt" "$base/robots.txt"
  python3 -I $B/robots.py "$W/robots-$id.txt" "$page" "$id"
done
```

Готовые файлы — в `catalog-seed/sources/`, плюс строка в таблицу `catalog-seed/README.md`.

### Когда страница из discovery — не страница тарифов

Кандидаты с «прямыми» страницами тарифов быстро заканчиваются. Тогда вместо шагов 2–3:

```bash
python3 -I $B/discover.py $W [каталог с уже скачанными pages]  # до 5 страниц на провайдера: $W/pages/<slug>~<k>.html
cut -f1 $W/variants.tsv > $W/auto.txt && PROBE_DIR=$W $RUN.auto
python3 -I $B/best.py $W                     # лучший вариант -> pages/<slug>.html, rest.tsv, auto.txt
PROBE_DIR=$W $RUN.auto                       # карточки уже только по выбранным страницам
```

`discover.py` берёт кандидатов из `all.tsv` (его пишет `candidates.py`, включая провайдеров с блогом
вместо страницы тарифов) и идёт по ссылкам сайта, похожим на VPS/VDS.

## Грабли, на которые уже наступали

- `INVALID … grouping must differ from the decimal separator` — при `grouping="."` нужен `decimal=","`.
- Единицы `ГБ`, `Гб`, `Go` парсер не знает: на `ramMb`/`diskGb` ставьте `unit="GB"`, иначе 2 ГБ
  превратятся в 2 МБ (`sanity.py` это ловит).
- `li:contains(IPv4)` + «IPv4: 1» прочитает 4 — берите элемент со значением или `V(1)`.
- `li:contains(…)` совпадает и с самой строкой-карточкой, если она тоже `li` — сужайте до
  `ul.<класс> li:contains(…)`.
- Одинаковые карточки на разных вкладках (локации, ОС) — `SnapshotValidator` отклонит дубли имён:
  сузьте корень до одной вкладки (`:lt(n)`, `div.tab.active`) или возьмите имя вместе с локацией.
  Если тарифы на странице показаны не все — `complete=False`.
- Классы Tailwind с `:`, `/`, `[` в селекторе не работают — цепляйтесь за структуру
  (`div.rounded-2xl:has(h3)`) или за `[class*=…]` (`~=` и `:matches` формат запрещает).
- `virtualization=KVM` ставьте константой, только если слово KVM есть на странице; иначе `UNKNOWN`.
- Цена «за месяц при оплате на 2–3 года» — не `MONTHLY`; ищите помесячную цену в атрибутах
  (`data-monthly`, `data-price`) или пропускайте сайт.
- Страница на другом хосте провайдера (`go.example.com`) — `ack=True`.
- `2,00 GB` разбирается как 200: запятая в числе считается разделителем тысяч. Такие сайты лучше
  пропустить или брать значение из атрибута.
- Число без единицы («16 ddr5») — `unit="GB"`, иначе выйдут мегабайты.
- Без имени тарифа на карточке — `"name": V("…")` плюс `"disambiguateNames": True` (имя дополнится
  диском/RAM); одинаковые по железу строки так не различить — сузьте выборку.
- Одна вёрстка на мобильный и десктоп даёт каждую карточку дважды: корень — первый контейнер
  (`div:has(> div.card)` берётся через `selectFirst`).
- Строки-заглушки, аддоны и «Configure your own» отсекайте в `rowSelector` через `:has(...)`
  с обязательным для тарифа элементом, и ставьте `complete=False`, если часть тарифов не берётся.
