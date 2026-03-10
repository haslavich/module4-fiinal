# film-cache-demo

1. Задача (технические требования)
```angular2html
- Оптимизация производительности с Hibernate и Redis
- демо проект на базе db (PostgreSQL)
- необходимо оптимизировать время наиболее частого запроса запроса города путем интеграции Redis 

- В реляционной БД эти данные хранятся в разных таблицах:
  - country
  - city
  - country_language
```

2. Решение
```angular2html
- Выгружаем агрегатированные данные (города+страна) в Redis

- Приложение обращается сначала к Redis и только при отсутствии данных - к БД

- Структура данных:
  - schema=world
  - country ( id,code,code_2,name,continent,region,surface_area,indep_year,population,life_expectancy,gnp,gnpo_id,local_name,government_form,head_of_state,capital)
  - city (id,name,country_id,district,population)
  - country_language (id,country_id,language,is_official,percentage) 

- Технологический стек:
  - Java 17 
  - Maven
  - Hibernate
  - PostgreSQL
  - Redis
  - P6Spy
  - Docker 

- Domain:
  - City
  - Country
  - Countrylanguage
- DAO - методы получения данных из PostgreSQL
- Redis DTO - класс CityCountry (плоская структура, готова для кэша)
- Загружаем города из postgreSQL, трансформируем в DTO, сохраняем в Redis, тестируем чтение
```

3. Запуск окружения через файл Docker-compose.yaml 

4. Апгрейд проекта
```angular2html
1) Предметная область: страны, города, языки
2) Связи: @OneToMany страна -> языки, @ManyToOne город -> страна, @OneToOne страна -> столица.
3) Проблема N+1: JOIN FETCH, с городами и странами
4) Кэшируемые данные: плоский DTO CityCountry(id города, название, население, данные страны, список языков)
5) Трансформация: ТЗ: City -> CityCountry
6) Ключ в Redis: <id города>
7) Тестирование: два теста, сравниваем время
```

5. Дополнительный функционал
```angular2html
1) Безопасное хранение логина и пароля:
   - в propertie-файле 
   - в переменных окружения требуется настроить значения db_password, db_user (в данном проекте файл properties - загружен в Git)
2) Профессиональный бенчмаркинг с JMH
3) Система миграций Flyway 
```