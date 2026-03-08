package com.javarush;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javarush.dao.CityDAO;
import com.javarush.dao.CountryDAO;
import com.javarush.domain.City;
import com.javarush.domain.Country;
import com.javarush.domain.CountryLanguage;
import com.javarush.redis.CityCountry;
import com.javarush.redis.Language;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisStringCommands;
import lombok.extern.slf4j.Slf4j;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.hibernate.cfg.Environment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.Objects.nonNull;
import static org.junit.jupiter.api.Assertions.*;

@Slf4j
class PerfomanceTest {
    private static SessionFactory sessionFactory;
    private  static CountryDAO countryDAO;
    private static CityDAO cityDAO;
    private static RedisClient redisClient;
    private static ObjectMapper mapper;
    private static List<Integer> testIDs=List.of(20, 45, 100, 250, 300, 400, 500, 600, 700);

    @BeforeAll
    static void setup() {
       sessionFactory=prepareRelationalDb();
       cityDAO=new CityDAO(sessionFactory);

        redisClient=RedisClient.create(RedisURI.create("localhost",6379));

        try (StatefulRedisConnection<String, String> connection = redisClient.connect()) {
            log.info("Установлено соединение с Redis");
        }
        mapper=new ObjectMapper();

        List<City> cities;

       try (Session session=sessionFactory.getCurrentSession()) {
           session.beginTransaction();
           int limit=cityDAO.getTotalCount();
           cities=cityDAO.getItems(0,limit);
       }
        List<CityCountry> cityCountries=transformData(cities);
       pushToRedis(cityCountries);
    }

    @AfterAll
    static void shotDown() {
        if (sessionFactory!=null && !sessionFactory.isClosed()) {
            sessionFactory.close();
        }
        if (nonNull(redisClient)) {
            redisClient.shutdown();
        }
    }

    @Test
    void redisTest () {
        long start=System.currentTimeMillis();
        try (StatefulRedisConnection<String, String> connection= redisClient.connect()) {
            RedisStringCommands<String, String> sync=connection.sync();
            for (Integer id: testIDs) {
                String json= sync.get("Country" + id);
                assertNotNull(json,"Данные для страны "+id+" не найдены в Redis");
                mapper.readValue(json, CityCountry.class);
            }
        } catch (JsonProcessingException e) {
            log.error("Ошибка"+e);
        }
        long duration=System.currentTimeMillis()-start;
        log.info("Redis чтение стран: "+duration+" ms");
    }

    @Test
    void PostgresqlTest() {
        long start=System.currentTimeMillis();
        try (Session session = sessionFactory.getCurrentSession()) {
            session.beginTransaction();
            for (Integer id : testIDs) {
                City city = cityDAO.getById(id);
                Set<CountryLanguage> languages = city.getCountry().getLanguages();
            }
            session.getTransaction().commit();
        }
        long duration=System.currentTimeMillis()-start;
        log.info("Postgresql чтение стран: "+duration+" ms");
    }

    private static void pushToRedis(List<CityCountry> data) {
        try (StatefulRedisConnection<String, String> connection = redisClient.connect()) {
            RedisStringCommands<String, String> sync = connection.sync();
            for (CityCountry cityCountry : data) {
                try {
                    sync.set("Country" + cityCountry.getId(), mapper.writeValueAsString(cityCountry));
                } catch (JsonProcessingException e) {
                    log.error ("Ошибка записи в Redis");
                }
            }
        }
    }

    private static List<CityCountry> transformData(List<City> cities) {
        return cities.stream().map(city -> {
            CityCountry res = new CityCountry();
            res.setId(city.getId());
            res.setName(city.getName());
            res.setPopulation(city.getPopulation());
            res.setDistrict(city.getDistrict());

            Country country = city.getCountry();
            res.setAlternativeCountryCode(country.getAlternativeCode());
            res.setContinent(country.getContinent());
            res.setCountryCode(country.getCode());
            res.setCountryName(country.getName());
            res.setCountryPopulation(country.getPopulation());
            res.setCountryRegion(country.getRegion());
            res.setCountrySurfaceArea(country.getSurfaceArea());
            Set<CountryLanguage> countryLanguages = country.getLanguages();
            Set<Language> languages = countryLanguages.stream().map(cl -> {
                Language language = new Language();
                language.setLanguage(cl.getLanguage());
                language.setIsOfficial(cl.getIsOfficial());
                language.setPercentage(cl.getPercentage());
                return language;
            }).collect(Collectors.toSet());
            res.setLanguages(languages);

            return res;
        }).collect(Collectors.toList());
    }

    private static SessionFactory prepareRelationalDb() {
        final SessionFactory sessionFactory;
        Properties properties = new Properties();
        properties.put(Environment.DIALECT, "org.hibernate.dialect.PostgreSQLDialect");
        properties.put(Environment.DRIVER, "org.postgresql.Driver");
        properties.put(Environment.URL, "jdbc:postgresql://localhost:5430/db");
        properties.put(Environment.USER, "user");
        properties.put(Environment.PASS, "password"); //todo передать пароль в маске
        properties.put(Environment.CURRENT_SESSION_CONTEXT_CLASS, "thread");
        properties.put(Environment.HBM2DDL_AUTO, "validate");
        properties.put(Environment.STATEMENT_BATCH_SIZE, "100");

        sessionFactory = new Configuration()
                .addAnnotatedClass(City.class)
                .addAnnotatedClass(Country.class)
                .addAnnotatedClass(CountryLanguage.class)
                .addProperties(properties)
                .buildSessionFactory();
        return sessionFactory;
    }
}