# OpenCart 4 — шпаргалка (встроенная)

## Где что лежит
Корень сайта — папка htdocs. Витрина: catalog/, админка: admin/, ядро: system/.
Шаблоны витрины (Twig): catalog/view/template/ — common/header.twig (шапка, логотип, меню), common/footer.twig (подвал),
common/home.twig (главная), product/product.twig (карточка товара), product/category.twig (категория), checkout/ (оформление).
Стили: catalog/view/stylesheet/stylesheet.css (свои правки лучше дописывать в КОНЕЦ этого файла). Bootstrap 5: catalog/view/stylesheet/bootstrap.css. Иконки — Font Awesome 6 (классы fa-solid fa-...).
Языковые файлы витрины: catalog/language/en-gb/ (например common/header.php, product/product.php) — это PHP-массивы $_['ключ'] = 'текст';
Картинки: image/catalog/ (в базе путь пишется без image/, например catalog/uploads/photo.jpg). Кеш миниатюр: image/cache/.
Кеш OpenCart: system/storage/cache/ — после правок шаблонов, языков и настроек удалить содержимое: rm -rf system/storage/cache/*
Конфиги: config.php и admin/config.php (не трогать без нужды).

## Цвета и дизайн
Самый безопасный способ — дописать CSS в конец catalog/view/stylesheet/stylesheet.css, например:
  header, #top { background:#0d47a1; } .btn-primary { background:#ff6f00; border-color:#ff6f00; }
  body { font-family: 'Inter', sans-serif; }
Шрифт Google Fonts подключается в common/header.twig внутри <head> через <link ...>.
Перед правкой: cp файл файл.bak. Проверить результат инструментом view_site("/").

## Настройки магазина (таблица oc_setting, store_id=0, code='config')
Ключи: config_name (название), config_owner, config_address, config_email, config_telephone, config_logo (путь картинки, напр. catalog/uploads/logo.jpg),
config_meta_title (заголовок вкладки), config_meta_description, config_currency (код валюты), config_language (код языка, напр. en-gb).
Пример: UPDATE oc_setting SET value='Мой магазин' WHERE store_id=0 AND `key`='config_name';
После изменения настроек — очистить кеш.

## Валюта
Таблица oc_currency (title, code, symbol_left, symbol_right, decimal_place, value, status, date_modified).
Добавить рубль:
  INSERT INTO oc_currency (title, code, symbol_left, symbol_right, decimal_place, value, status, date_modified) VALUES ('Рубль','RUB','',' ₽','2',1.0,1,NOW());
Сделать основной: UPDATE oc_setting SET value='RUB' WHERE store_id=0 AND `key`='config_currency';
Основная валюта должна иметь value=1.0.

## Товары
Перед вставкой посмотреть структуру: DESCRIBE oc_product; и пример строки: SELECT * FROM oc_product LIMIT 1\G
Основные таблицы: oc_product (model, sku, quantity, stock_status_id, image, price, status, date_available, date_added, date_modified, ...),
oc_product_description (product_id, language_id, name, description, tag, meta_title, meta_description, meta_keyword),
oc_product_to_store (product_id, store_id=0), oc_product_to_category (product_id, category_id), oc_product_image (доп. фото),
oc_seo_url (store_id, language_id, `key`='product_id', `value`=id товара, keyword) — ЧПУ.
language_id основного языка — смотри SELECT language_id, code FROM oc_language;
Минимальный рецепт:
  INSERT INTO oc_product SET model='M1', quantity=10, stock_status_id=7, image='catalog/uploads/x.jpg', price=999, status=1, date_available=CURDATE(), date_added=NOW(), date_modified=NOW(), shipping=1, subtract=1, minimum=1, tax_class_id=0, weight_class_id=1, length_class_id=1;
  SET @id = LAST_INSERT_ID();
  INSERT INTO oc_product_description SET product_id=@id, language_id=1, name='Название', description='Описание', meta_title='Название', tag='', meta_description='', meta_keyword='';
  INSERT INTO oc_product_to_store SET product_id=@id, store_id=0;
Все команды одной строкой через mariadb -e "...;...;" (переменная @id живёт в одном подключении).
Цена/наличие: UPDATE oc_product SET price=..., quantity=... WHERE product_id=...;

## Категории
oc_category, oc_category_description (category_id, language_id, name, description, meta_title), oc_category_to_store, oc_category_path (category_id, path_id, level).
Проще создавать категории в админке; если через SQL — не забыть oc_category_path (для корневой: category_id=id, path_id=id, level=0).

## Главная страница, баннеры, модули
Модули раскладки: oc_layout_module (layout_id, code, position, sort_order); Home — layout_id из oc_layout (name='Home').
Настройки модулей (featured, slideshow, banner): oc_module (module_id, name, code, setting — JSON).
Баннеры/слайды: oc_banner, oc_banner_image (banner_id, language_id, title, link, image, sort_order).

## Информационные страницы (О нас, Доставка)
oc_information, oc_information_description (information_id, language_id, title, description, meta_title).

## Админка
Вход: /admin/. Сброс пароля:
  php -r 'echo password_hash("новый", PASSWORD_DEFAULT);'  → UPDATE oc_user SET password='хеш' WHERE username='admin';

## Частые ошибки
- Правка не видна → очистить system/storage/cache/* и обновить страницу.
- Белый экран/500 → смотри логи: system/storage/logs/error.log и логи приложения.
- Twig: переменные {{ name }}, условия {% if %}...{% endif %}; не ломать парные теги.
