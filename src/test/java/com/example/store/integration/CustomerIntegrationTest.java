package com.example.store.integration;

import com.example.store.controller.Pagination;
import com.example.store.entity.Customer;
import com.example.store.repository.CustomerRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CustomerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createCustomerOnSeededDatabaseGetsIdAfterSeedData() throws Exception {
        String response = mockMvc.perform(post("/customer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Integration Customer\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode created = objectMapper.readTree(response);
        // The seed data uses ids 1..100; anything else means the sequence was not advanced.
        assertThat(created.get("id").asLong()).isGreaterThan(100L);
        assertThat(created.get("name").asText()).isEqualTo("Integration Customer");
        assertThat(created.get("orders")).isEmpty();
    }

    @Test
    void getCustomersKeepsTheExistingJsonShape() throws Exception {
        JsonNode customers = readArray(mockMvc.perform(get("/customer"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse());

        JsonNode customer = StreamSupport.stream(customers.spliterator(), false)
                .filter(node -> node.get("id").asLong() == 1L)
                .findFirst()
                .orElseThrow();
        assertThat(fieldNames(customer)).containsExactlyInAnyOrder("id", "name", "orders");
        assertThat(customer.get("name").asText()).isEqualTo("Muriel Donnelly");

        JsonNode order = StreamSupport.stream(customer.get("orders").spliterator(), false)
                .filter(node -> node.get("id").asLong() == 158L)
                .findFirst()
                .orElseThrow();
        assertThat(fieldNames(order)).containsExactlyInAnyOrder("id", "description");
        assertThat(order.get("description").asText()).isEqualTo("Awesome Concrete Shirt");
    }

    @Test
    void getCustomersReturnsDefaultPageOfTwentyOrderedByIdWithOrdersOrderedById() throws Exception {
        JsonNode customers = readArray(mockMvc.perform(get("/customer"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse());

        assertThat(ids(customers)).hasSize(20).isSorted().startsWith(1L);
        for (JsonNode customer : customers) {
            assertThat(ids(customer.get("orders"))).isSorted();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 20, 100})
    void getCustomersUsesAtMostThreeStatementsWhateverThePageSize(int size) throws Exception {
        MvcResult[] result = new MvcResult[1];

        SqlStatementCounter.Result sql = sqlStatementCounter()
                .measure(() -> result[0] = mockMvc.perform(get("/customer").param("size", String.valueOf(size)))
                        .andExpect(status().isOk())
                        .andReturn());

        // Page of customers + count + one IN query for all their orders; no lazy collection initialisation.
        assertThat(sql.statements()).isLessThanOrEqualTo(3);
        assertThat(sql.collectionFetches()).isZero();
        JsonNode customers = readArray(result[0].getResponse());
        assertThat(customers).hasSize(size);
        // Every seeded customer has orders: proves they were actually loaded, not silently dropped.
        assertThat(customers.get(0).get("orders")).isNotEmpty();
    }

    @Test
    void getCustomersExposesTotalCountAndLinks() throws Exception {
        long total = customerRepository.count();
        long last = (total - 1) / 10;

        MockHttpServletResponse response = mockMvc.perform(
                        get("/customer").param("page", "1").param("size", "10"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();

        assertThat(response.getHeader("X-Total-Count")).isEqualTo(String.valueOf(total));
        assertThat(links(response.getHeader("Link")))
                .containsExactly(
                        Map.entry("first", "http://localhost/customer?page=0&size=10"),
                        Map.entry("prev", "http://localhost/customer?page=0&size=10"),
                        Map.entry("next", "http://localhost/customer?page=2&size=10"),
                        Map.entry("last", "http://localhost/customer?page=" + last + "&size=10"));
        assertThat(ids(readArray(response))).containsExactly(11L, 12L, 13L, 14L, 15L, 16L, 17L, 18L, 19L, 20L);
    }

    @Test
    void getCustomersClampsSizeToOneHundred() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get("/customer").param("size", "1000"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();

        assertThat(readArray(response)).hasSize(100);
        assertThat(links(response.getHeader("Link")).get("first")).endsWith("?page=0&size=100");
    }

    @Test
    void getCustomersWithNegativePageReturns400() throws Exception {
        mockMvc.perform(get("/customer").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[0].field").value("page"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/customer", "/customer?name=a", "/order", "/products"})
    void collectionsRejectAPageWhoseOffsetOverflowsAndServeTheHighestPage(String path) throws Exception {
        mockMvc.perform(get(path)
                        .param("page", String.valueOf(Integer.MAX_VALUE))
                        .param("size", "100"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[0].field").value("page"));

        mockMvc.perform(get(path)
                        .param("page", String.valueOf(Pagination.MAX_PAGE))
                        .param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"MURIEL", "muriel", "mUrIeL DONNELLY", "  Muriel  "})
    void searchIsCaseInsensitiveAndTrimsTheTerm(String name) throws Exception {
        JsonNode customers = search(name);

        assertThat(ids(customers)).contains(1L);
        assertThat(names(customers)).allSatisfy(n -> assertThat(n.toLowerCase()).contains("muriel"));
    }

    @Test
    void searchMatchesASubstringInTheMiddleOfAWord() throws Exception {
        JsonNode customers = search("onnel");

        assertThat(ids(customers)).contains(1L);
        assertThat(names(customers)).allSatisfy(n -> assertThat(n).containsIgnoringCase("onnel"));
        JsonNode muriel = StreamSupport.stream(customers.spliterator(), false)
                .filter(node -> node.get("id").asLong() == 1L)
                .findFirst()
                .orElseThrow();
        assertThat(fieldNames(muriel)).containsExactlyInAnyOrder("id", "name", "orders");
        assertThat(muriel.get("name").asText()).isEqualTo("Muriel Donnelly");
        assertThat(muriel.get("orders")).isNotEmpty();
    }

    @Test
    void searchTreatsPercentAsALiteral() throws Exception {
        String token = token();
        Customer literal = saveCustomer("Pct%" + token);
        // Would also match if % were a wildcard.
        saveCustomer("Pct and more " + token);

        assertThat(ids(search("pct%" + token))).containsExactly(literal.getId());
        // A lone % matches only names that contain a literal %, not every customer.
        JsonNode percent = search("%", "100");
        assertThat(ids(percent)).contains(literal.getId());
        assertThat(names(percent)).allSatisfy(n -> assertThat(n).contains("%"));
    }

    @Test
    void searchTreatsUnderscoreAsALiteral() throws Exception {
        String token = token();
        Customer literal = saveCustomer("Lit_" + token);
        // Would also match if _ were a single-character wildcard.
        saveCustomer("LitX" + token);

        assertThat(ids(search("lit_" + token))).containsExactly(literal.getId());
        assertThat(names(search("_", "100"))).allSatisfy(n -> assertThat(n).contains("_"));
    }

    @Test
    void searchTreatsTheEscapeCharacterAsALiteral() throws Exception {
        String token = token();
        Customer literal = saveCustomer("Back\\slash" + token);
        saveCustomer("Backslash" + token);

        assertThat(ids(search("k\\s"))).contains(literal.getId());
        assertThat(ids(search("back\\slash" + token))).containsExactly(literal.getId());
    }

    @Test
    void searchWithNoMatchReturnsEmptyArrayAndZeroTotal() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get("/customer").param("name", "no such " + token()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();

        assertThat(readArray(response)).isEmpty();
        assertThat(response.getHeader("X-Total-Count")).isEqualTo("0");
        assertThat(links(response.getHeader("Link"))).containsOnlyKeys("first", "last");
    }

    @Test
    void searchResultsArePaginatedWithHeadersThatKeepTheName() throws Exception {
        Long total =
                jdbcTemplate.queryForObject("SELECT count(*) FROM customer WHERE lower(name) LIKE '%a%'", Long.class);
        long last = (total - 1) / 5;
        assertThat(last).as("the seed data has enough names containing 'a'").isGreaterThan(1);

        // In the URL rather than via param(): MockMvc only fills the query string, which the links are built from,
        // then.
        MockHttpServletResponse response = mockMvc.perform(get("/customer?name=A&page=1&size=5"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();

        assertThat(response.getHeader("X-Total-Count")).isEqualTo(String.valueOf(total));
        assertThat(links(response.getHeader("Link")))
                .containsExactly(
                        Map.entry("first", "http://localhost/customer?name=A&page=0&size=5"),
                        Map.entry("prev", "http://localhost/customer?name=A&page=0&size=5"),
                        Map.entry("next", "http://localhost/customer?name=A&page=2&size=5"),
                        Map.entry("last", "http://localhost/customer?name=A&page=" + last + "&size=5"));
        JsonNode customers = readArray(response);
        assertThat(ids(customers)).hasSize(5).isSorted();
        assertThat(names(customers)).allSatisfy(n -> assertThat(n).containsIgnoringCase("a"));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 20, 100})
    void searchUsesAtMostThreeStatementsWhateverThePageSize(int size) throws Exception {
        MvcResult[] result = new MvcResult[1];

        SqlStatementCounter.Result sql = sqlStatementCounter()
                .measure(() -> result[0] = mockMvc.perform(
                                get("/customer").param("name", "e").param("size", String.valueOf(size)))
                        .andExpect(status().isOk())
                        .andReturn());

        // Same fetch plan as the list: matching page + count + one IN query for their orders.
        assertThat(sql.statements()).isLessThanOrEqualTo(3);
        assertThat(sql.collectionFetches()).isZero();
        JsonNode customers = readArray(result[0].getResponse());
        assertThat(customers).isNotEmpty().hasSizeLessThanOrEqualTo(size);
        assertThat(customers.get(0).get("orders")).isNotEmpty();
    }

    @Test
    void searchWithBlankNameReturns400() throws Exception {
        mockMvc.perform(get("/customer").param("name", "   "))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\u2003", "\u3000"})
    void searchWithUnicodeWhitespaceNameReturns400InsteadOfEveryCustomer(String name) throws Exception {
        mockMvc.perform(get("/customer").param("name", name))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }

    @Test
    void searchWithNulInNameReturns400() throws Exception {
        mockMvc.perform(get("/customer").param("name", "\u0000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }

    @Test
    void createCustomerWithNulInNameReturns400AndPersistsNothing() throws Exception {
        long before = customerRepository.count();

        mockMvc.perform(post("/customer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"John\\u0000Doe\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("name"));

        assertThat(customerRepository.count()).isEqualTo(before);
    }

    @Test
    void createCustomerWithDuplicateKeyReturns400() throws Exception {
        mockMvc.perform(post("/customer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"name\":\"y\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    private JsonNode search(String name) throws Exception {
        return search(name, "20");
    }

    private JsonNode search(String name, String size) throws Exception {
        return readArray(mockMvc.perform(get("/customer").param("name", name).param("size", size))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse());
    }

    private Customer saveCustomer(String name) {
        Customer customer = new Customer();
        customer.setName(name);
        return customerRepository.save(customer);
    }

    /** Makes the names created by one test unique, as the database is shared by all tests. */
    private static String token() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static List<String> names(JsonNode array) {
        List<String> names = new ArrayList<>();
        array.forEach(node -> names.add(node.get("name").asText()));
        return names;
    }

    private JsonNode readArray(MockHttpServletResponse response) throws Exception {
        JsonNode node = objectMapper.readTree(response.getContentAsString());
        assertThat(node.isArray()).isTrue();
        return node;
    }

    private static List<Long> ids(JsonNode array) {
        List<Long> ids = new ArrayList<>();
        array.forEach(node -> ids.add(node.get("id").asLong()));
        return ids;
    }
}
