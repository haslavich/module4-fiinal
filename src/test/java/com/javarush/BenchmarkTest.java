package com.javarush;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javarush.dao.CityDAO;
import com.javarush.domain.City;
import com.javarush.domain.Country;
import com.javarush.domain.CountryLanguage;
import com.javarush.redis.CityCountry;
import com.javarush.redis.Language;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisStringCommands;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.hibernate.cfg.Environment;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static java.util.Objects.nonNull;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2,time = 5)
@Measurement(iterations = 3,time = 5)
@Fork(1)
public class BenchmarkTest {
    private SessionFactory sessionFactory;
    private CityDAO cityDAO;
    private RedisClient redisClient;
    private ObjectMapper objectMapper;
    private List<Integer> testIDs=List.of(20, 45, 100, 250, 300, 400, 500, 600, 700);

    public static void main(String[] args) {
        Options options=new OptionsBuilder()
                .include(BenchmarkTest.class.getSimpleName())
                .build();
        try {
            new Runner(options).run();
        } catch (RunnerException e) {
            throw new RuntimeException(e);
        }
    }

    @Setup(Level.Trial)
    public void setup() {
        sessionFactory = prepareRelationalDb();
        cityDAO = new CityDAO(sessionFactory);
        redisClient = RedisClient.create(RedisURI.create("localhost", 6379));
        objectMapper = new ObjectMapper();

        try (Session session = sessionFactory.getCurrentSession()) {
        session.beginTransaction();
        List<City> cities = cityDAO.getItems(0, cityDAO.getTotalCount());
        session.getTransaction().commit();

        List<CityCountry> cityCountries = cities.stream().map(
                city -> {
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

        try (StatefulRedisConnection<String, String> connection = redisClient.connect()) {
            RedisStringCommands<String, String> sync = connection.sync();
            for (CityCountry cityCountry : cityCountries) {
                try {
                    sync.set("Country" + cityCountry.getId(), objectMapper.writeValueAsString(cityCountry));
                } catch (JsonProcessingException e) {
                    throw new RuntimeException(e);
                }
            }
            }
        }
    }

    @TearDown(Level.Trial)
    public void shotDown() {
        if (sessionFactory!=null) {
            sessionFactory.close();
        }
        if (nonNull(redisClient)) {
            redisClient.close();
        }
    }

    @Benchmark
    public void readFromRedis() {
        try (StatefulRedisConnection<String,String> connection=redisClient.connect()) {
            RedisStringCommands<String,String> sync= connection.sync();
            for (Integer id: testIDs) {
                String json= sync.get("Country" + id);
                objectMapper.readValue(json, CityCountry.class);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Benchmark
    public void readFromPostgresql() {
        try (Session session=sessionFactory.getCurrentSession()) {
            session.beginTransaction();
            for(Integer id:testIDs){
                City city=cityDAO.getById(id);
                city.getCountry().getLanguages().size();
            }
        }
    }

    private static SessionFactory prepareRelationalDb() {
        final SessionFactory sessionFactory;
        Properties properties = new Properties();
        properties.put(Environment.DIALECT, "org.hibernate.dialect.PostgreSQLDialect");
        properties.put(Environment.DRIVER, "org.postgresql.Driver");
        properties.put(Environment.URL, "jdbc:postgresql://localhost:5430/db");
        properties.put(Environment.USER,"user");
        properties.put(Environment.PASS,"password");
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