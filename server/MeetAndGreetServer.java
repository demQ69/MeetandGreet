import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.Executors;

/**
 * Автономный сервер Meet-and-Greet на чистой Java (JDK HttpServer, без сторонних библиотек).
 * Хранит общую базу студентов, лайков, мэтчей, чатов, жалоб и фотографий в папке ./data/
 *
 * Запуск: java MeetAndGreetServer.java
 */
public class MeetAndGreetServer {

    private static final int PORT = 8080;
    private static final String COLLEGE_DOMAIN = "@itmoscow.pro";

    private static final Path DATA_DIR = Paths.get("data");
    private static final Path AVATARS_DIR = DATA_DIR.resolve("avatars");
    private static final Path DB_FILE = DATA_DIR.resolve("db.json");

    private static final Object DB_LOCK = new Object();

    private static int nextStudentId = 1;
    private static int nextLikeId = 1;
    private static int nextMatchId = 1;
    private static int nextMessageId = 1;
    private static int nextReportId = 1;

    private static final List<Map<String, Object>> students = new ArrayList<>();
    private static final List<Map<String, Object>> likes = new ArrayList<>();
    private static final List<Map<String, Object>> matches = new ArrayList<>();
    private static final List<Map<String, Object>> messages = new ArrayList<>();
    private static final List<Map<String, Object>> reports = new ArrayList<>();
    private static final Map<String, List<Long>> failedAttempts = new HashMap<>();

    private static final Set<String> DEMO_BOT_EMAILS = Set.of(
        "anna@itmoscow.pro",
        "alex@itmoscow.pro",
        "bob@itmoscow.pro",
        "clara@itmoscow.pro",
        "denis@itmoscow.pro",
        "elena@itmoscow.pro"
    );

    public static void main(String[] args) throws Exception {
        int port = PORT;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException ignored) {}
        }

        Files.createDirectories(AVATARS_DIR);
        synchronized (DB_LOCK) {
            loadOrSeedDatabase();
        }

        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/api/", MeetAndGreetServer::handleApiRequest);
        server.createContext("/", exchange -> {
            sendJson(exchange, 200, mapOf(
                "ok", true,
                "app", "Meet-and-Greet Server",
                "students_count", students.size()
            ));
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        System.out.println("====================================================");
        System.out.println("  Meet-and-Greet Java Server is RUNNING!");
        System.out.println("  Local URL: http://localhost:" + port);
        System.out.println("  Data dir:  " + DATA_DIR.toAbsolutePath());
        System.out.println("====================================================");
        System.out.println("Wait for mobile connections via cloudflared / Wi-Fi...");
    }

    private static void handleApiRequest(HttpExchange exchange) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Access-Control-Allow-Origin", "*");
        headers.set("Access-Control-Allow-Headers", "Content-Type");
        headers.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }

        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getRawQuery();
        Map<String, String> params = parseQuery(query);
        String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);

        try {
            if ("GET".equals(method) && "/api/health".equals(path)) {
                synchronized (DB_LOCK) {
                    sendJson(exchange, 200, mapOf(
                        "ok", true,
                        "app", "Meet-and-Greet",
                        "students", students.size()
                    ));
                }
                return;
            }

            if ("GET".equals(method) && path.startsWith("/api/avatar/")) {
                String fileName = path.substring("/api/avatar/".length()).replaceAll("[^a-zA-Z0-9._-]", "");
                Path file = AVATARS_DIR.resolve(fileName);
                if (!fileName.isEmpty() && Files.exists(file)) {
                    byte[] bytes = Files.readAllBytes(file);
                    exchange.getResponseHeaders().set("Content-Type", "image/jpeg");
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(bytes);
                    }
                    return;
                }
                sendJson(exchange, 404, mapOf("error", "Avatar not found"));
                return;
            }

            if ("POST".equals(method)) {
                String rawBody = readBody(exchange.getRequestBody());
                Map<String, Object> body = rawBody.isBlank()
                    ? new LinkedHashMap<>()
                    : asMap(SimpleJson.parse(rawBody));

                switch (path) {
                    case "/api/login":
                        handleLogin(exchange, body);
                        return;
                    case "/api/register":
                        handleRegister(exchange, body);
                        return;
                    case "/api/profile":
                        handleUpdateProfile(exchange, body);
                        return;
                    case "/api/delete_account":
                        handleDeleteAccount(exchange, body);
                        return;
                    case "/api/swipe":
                        handleSwipe(exchange, body);
                        return;
                    case "/api/revoke_like":
                        handleRevokeLike(exchange, body);
                        return;
                    case "/api/reset_swipes":
                        handleResetSwipes(exchange, body);
                        return;
                    case "/api/unmatch":
                        handleUnmatch(exchange, body);
                        return;
                    case "/api/send_message":
                        handleSendMessage(exchange, body);
                        return;
                    case "/api/report":
                        handleReport(exchange, body);
                        return;
                    default:
                        sendJson(exchange, 404, mapOf("error", "Unknown endpoint: " + path));
                        return;
                }
            }

            if ("GET".equals(method)) {
                switch (path) {
                    case "/api/student": {
                        int id = parseInt(params.get("id"), -1);
                        synchronized (DB_LOCK) {
                            Map<String, Object> st = findStudentById(id);
                            if (st == null) {
                                sendJson(exchange, 404, mapOf("error", "Student not found"));
                            } else {
                                sendJson(exchange, 200, publicStudent(st));
                            }
                        }
                        return;
                    }
                    case "/api/candidates": {
                        int studentId = parseInt(params.get("student_id"), -1);
                        handleGetCandidates(exchange, studentId);
                        return;
                    }
                    case "/api/outgoing_likes": {
                        int studentId = parseInt(params.get("student_id"), -1);
                        handleGetOutgoingLikes(exchange, studentId);
                        return;
                    }
                    case "/api/incoming_likes": {
                        int studentId = parseInt(params.get("student_id"), -1);
                        handleGetIncomingLikes(exchange, studentId);
                        return;
                    }
                    case "/api/rating": {
                        handleGetRating(exchange);
                        return;
                    }
                    case "/api/matches": {
                        int studentId = parseInt(params.get("student_id"), -1);
                        handleGetMatches(exchange, studentId);
                        return;
                    }
                    case "/api/messages": {
                        int matchId = parseInt(params.get("match_id"), -1);
                        handleGetMessages(exchange, matchId);
                        return;
                    }
                    default:
                        sendJson(exchange, 404, mapOf("error", "Unknown endpoint: " + path));
                        return;
                }
            }

            sendJson(exchange, 405, mapOf("error", "Method not allowed"));
        } catch (Exception e) {
            e.printStackTrace();
            sendJson(exchange, 500, mapOf("error", "Server error: " + e.getMessage()));
        }
    }

    // ======================= HANDLERS =======================

    private static void handleLogin(HttpExchange exchange, Map<String, Object> body) throws IOException {
        String email = str(body.get("email")).trim().toLowerCase(Locale.ROOT);
        String password = str(body.get("password"));

        if (!isCollegeEmail(email)) {
            sendJson(exchange, 400, mapOf("error", "Доступ только для студентов колледжа! Используйте корпоративную почту " + COLLEGE_DOMAIN));
            return;
        }

        synchronized (DB_LOCK) {
            long now = System.currentTimeMillis();
            long limit = now - 15 * 60 * 1000L;
            List<Long> attempts = failedAttempts.computeIfAbsent(email, k -> new ArrayList<>());
            attempts.removeIf(t -> t <= limit);
            if (attempts.size() >= 5) {
                sendJson(exchange, 429, mapOf("error", "Слишком много попыток входа. Подождите 15 минут."));
                return;
            }

            Map<String, Object> student = findStudentByEmail(email);
            if (student == null) {
                attempts.add(now);
                sendJson(exchange, 401, mapOf("error", "Аккаунт с таким email не найден. Зарегистрируйтесь!"));
                return;
            }

            if (!str(student.get("password_hash")).equals(hashPassword(password))) {
                attempts.add(now);
                sendJson(exchange, 401, mapOf("error", "Неверный пароль"));
                return;
            }

            attempts.clear();
            System.out.println("[LOGIN] " + email + " (" + str(student.get("full_name")) + ")");
            sendJson(exchange, 200, publicStudent(student));
        }
    }

    private static void handleRegister(HttpExchange exchange, Map<String, Object> body) throws IOException {
        String email = str(body.get("email")).trim().toLowerCase(Locale.ROOT);
        String password = str(body.get("password"));
        String name = str(body.get("full_name")).trim();
        String faculty = str(body.get("faculty")).trim();
        String studyGroup = str(body.get("study_group")).trim();
        int age = clamp(asInt(body.get("age"), 18), 15, 60);
        String bio = str(body.get("bio")).trim();
        String interests = str(body.get("interests")).trim();
        String lookingFor = str(body.get("looking_for")).trim();
        String photoBase64 = str(body.get("photo_base64"));

        if (!isCollegeEmail(email)) {
            sendJson(exchange, 400, mapOf("error", "Регистрация доступна только студентам колледжа с почтой " + COLLEGE_DOMAIN));
            return;
        }
        if (password.length() < 4) {
            sendJson(exchange, 400, mapOf("error", "Пароль должен содержать минимум 4 символа"));
            return;
        }
        if (name.length() < 2) {
            sendJson(exchange, 400, mapOf("error", "Введите ваше имя (минимум 2 буквы)"));
            return;
        }

        synchronized (DB_LOCK) {
            if (findStudentByEmail(email) != null) {
                sendJson(exchange, 409, mapOf("error", "Студент с таким Email уже зарегистрирован"));
                return;
            }

            int newId = nextStudentId++;
            String photoUri = saveAvatarIfPresent(newId, photoBase64);

            Map<String, Object> st = createStudentMap(
                newId,
                email,
                hashPassword(password),
                name,
                faculty.isEmpty() ? "Информационные технологии" : faculty,
                studyGroup.isEmpty() ? "ИС-21" : studyGroup,
                age,
                bio.isEmpty() ? "Люблю рисовать и читать" : bio,
                interests.isEmpty() ? "Рисование, Чтение" : interests,
                lookingFor.isEmpty() ? "relationship" : lookingFor,
                photoUri,
                15
            );
            students.add(st);

            // Добавляем входящий лайк от демо-анкет, чтобы новичок мог сразу проверить вкладку «Вам» и мэтчи
            Map<String, Object> alex = findStudentByEmail("alex@itmoscow.pro");
            if (alex != null) {
                addLikeInternal(asInt(alex.get("id"), 0), newId, true);
            }

            saveDatabase();
            System.out.println("[REGISTER] New student #" + newId + ": " + email + " (" + name + ")");
            sendJson(exchange, 200, publicStudent(st));
        }
    }

    private static void handleUpdateProfile(HttpExchange exchange, Map<String, Object> body) throws IOException {
        int id = asInt(body.get("id"), -1);
        synchronized (DB_LOCK) {
            Map<String, Object> st = findStudentById(id);
            if (st == null) {
                sendJson(exchange, 404, mapOf("error", "Student not found"));
                return;
            }

            String fullName = str(body.get("full_name")).trim();
            if (!fullName.isEmpty()) st.put("full_name", fullName);
            st.put("age", clamp(asInt(body.get("age"), asInt(st.get("age"), 18)), 15, 60));

            String faculty = str(body.get("faculty")).trim();
            if (!faculty.isEmpty()) st.put("faculty", faculty);

            String studyGroup = str(body.get("study_group")).trim();
            if (!studyGroup.isEmpty()) st.put("study_group", studyGroup);

            String bio = str(body.get("bio")).trim();
            if (!bio.isEmpty()) st.put("bio", bio);

            String interests = str(body.get("interests")).trim();
            if (!interests.isEmpty()) st.put("interests", interests);

            String lookingFor = str(body.get("looking_for")).trim();
            if (!lookingFor.isEmpty()) st.put("looking_for", lookingFor);

            if (Boolean.TRUE.equals(body.get("clear_photo"))) {
                st.put("photo_uri", null);
            } else {
                String photoBase64 = str(body.get("photo_base64"));
                if (!photoBase64.isEmpty()) {
                    String photoUri = saveAvatarIfPresent(id, photoBase64);
                    if (photoUri != null) {
                        st.put("photo_uri", photoUri);
                    }
                }
            }

            saveDatabase();
            sendJson(exchange, 200, publicStudent(st));
        }
    }

    private static void handleDeleteAccount(HttpExchange exchange, Map<String, Object> body) throws IOException {
        int studentId = asInt(body.get("student_id"), -1);
        synchronized (DB_LOCK) {
            students.removeIf(s -> asInt(s.get("id"), -1) == studentId);
            likes.removeIf(l -> asInt(l.get("from_student_id"), -1) == studentId || asInt(l.get("to_student_id"), -1) == studentId);
            List<Integer> removedMatchIds = new ArrayList<>();
            matches.removeIf(m -> {
                boolean hit = asInt(m.get("student_a_id"), -1) == studentId || asInt(m.get("student_b_id"), -1) == studentId;
                if (hit) removedMatchIds.add(asInt(m.get("id"), -1));
                return hit;
            });
            messages.removeIf(msg -> removedMatchIds.contains(asInt(msg.get("match_id"), -1)));
            reports.removeIf(r -> asInt(r.get("reporter_id"), -1) == studentId || asInt(r.get("reported_student_id"), -1) == studentId);
            saveDatabase();
            sendJson(exchange, 200, mapOf("ok", true));
        }
    }

    private static void handleGetCandidates(HttpExchange exchange, int currentId) throws IOException {
        synchronized (DB_LOCK) {
            Set<Integer> swipedIds = new HashSet<>();
            for (Map<String, Object> l : likes) {
                if (asInt(l.get("from_student_id"), -1) == currentId) {
                    swipedIds.add(asInt(l.get("to_student_id"), -1));
                }
            }
            Set<Integer> blockedIds = getBlockedIdsFor(currentId);

            List<Map<String, Object>> result = new ArrayList<>();
            for (Map<String, Object> st : students) {
                int id = asInt(st.get("id"), -1);
                if (id != currentId && !swipedIds.contains(id) && !blockedIds.contains(id)) {
                    result.add(publicStudent(st));
                }
            }
            // Поднимаем реальных зарегистрированных студентов и Александра вверх
            result.sort((a, b) -> {
                boolean aReal = !DEMO_BOT_EMAILS.contains(str(a.get("email")));
                boolean bReal = !DEMO_BOT_EMAILS.contains(str(b.get("email")));
                if (aReal != bReal) return aReal ? -1 : 1;
                return Integer.compare(asInt(a.get("id"), 0), asInt(b.get("id"), 0));
            });
            sendJson(exchange, 200, result);
        }
    }

    private static void handleGetOutgoingLikes(HttpExchange exchange, int currentId) throws IOException {
        synchronized (DB_LOCK) {
            List<Map<String, Object>> result = new ArrayList<>();
            Set<Integer> likedIds = new LinkedHashSet<>();
            for (Map<String, Object> l : likes) {
                if (asInt(l.get("from_student_id"), -1) == currentId && Boolean.TRUE.equals(l.get("is_like"))) {
                    likedIds.add(asInt(l.get("to_student_id"), -1));
                }
            }
            for (int id : likedIds) {
                Map<String, Object> st = findStudentById(id);
                if (st != null) result.add(publicStudent(st));
            }
            sendJson(exchange, 200, result);
        }
    }

    private static void handleGetIncomingLikes(HttpExchange exchange, int currentId) throws IOException {
        synchronized (DB_LOCK) {
            Set<Integer> matchedPartners = new HashSet<>();
            for (Map<String, Object> m : matches) {
                int a = asInt(m.get("student_a_id"), -1);
                int b = asInt(m.get("student_b_id"), -1);
                if (a == currentId) matchedPartners.add(b);
                if (b == currentId) matchedPartners.add(a);
            }
            Set<Integer> blockedIds = getBlockedIdsFor(currentId);

            List<Map<String, Object>> result = new ArrayList<>();
            Set<Integer> seen = new HashSet<>();
            for (Map<String, Object> l : likes) {
                if (asInt(l.get("to_student_id"), -1) == currentId && Boolean.TRUE.equals(l.get("is_like"))) {
                    int fromId = asInt(l.get("from_student_id"), -1);
                    if (!matchedPartners.contains(fromId) && !blockedIds.contains(fromId) && seen.add(fromId)) {
                        Map<String, Object> st = findStudentById(fromId);
                        if (st != null) result.add(publicStudent(st));
                    }
                }
            }
            sendJson(exchange, 200, result);
        }
    }

    private static void handleGetRating(HttpExchange exchange) throws IOException {
        synchronized (DB_LOCK) {
            List<Map<String, Object>> list = new ArrayList<>();
            for (Map<String, Object> st : students) {
                list.add(publicStudent(st));
            }
            list.sort((a, b) -> {
                int cmp = Integer.compare(asInt(b.get("likes_received"), 0), asInt(a.get("likes_received"), 0));
                if (cmp != 0) return cmp;
                return Integer.compare(asInt(a.get("id"), 0), asInt(b.get("id"), 0));
            });
            sendJson(exchange, 200, list);
        }
    }

    private static void handleSwipe(HttpExchange exchange, Map<String, Object> body) throws IOException {
        int currentId = asInt(body.get("from_id"), -1);
        int targetId = asInt(body.get("to_id"), -1);
        boolean isLike = Boolean.TRUE.equals(body.get("is_like"));

        synchronized (DB_LOCK) {
            boolean alreadyPositive = hasPositiveLike(currentId, targetId);
            likes.removeIf(l -> asInt(l.get("from_student_id"), -1) == currentId && asInt(l.get("to_student_id"), -1) == targetId);
            addLikeInternal(currentId, targetId, isLike);

            if (isLike && !alreadyPositive) {
                Map<String, Object> target = findStudentById(targetId);
                if (target != null) {
                    target.put("likes_received", asInt(target.get("likes_received"), 0) + 1);
                }
            }

            Map<String, Object> matchedPartner = null;
            if (isLike && hasPositiveLike(targetId, currentId) && findExistingMatch(currentId, targetId) == null) {
                int a = Math.min(currentId, targetId);
                int b = Math.max(currentId, targetId);
                int matchId = nextMatchId++;
                Map<String, Object> m = mapOf(
                    "id", matchId,
                    "student_a_id", a,
                    "student_b_id", b,
                    "created_at", System.currentTimeMillis()
                );
                matches.add(m);

                Map<String, Object> partner = findStudentById(targetId);
                if (partner != null) {
                    matchedPartner = publicStudent(partner);
                    if (DEMO_BOT_EMAILS.contains(str(partner.get("email")))) {
                        messages.add(mapOf(
                            "id", nextMessageId++,
                            "match_id", matchId,
                            "sender_id", targetId,
                            "body", "Привет! У нас совпали интересы в Meet-and-Greet 😊",
                            "sent_at", System.currentTimeMillis()
                        ));
                    }
                }
            }

            saveDatabase();
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("matched_partner", matchedPartner);
            sendJson(exchange, 200, resp);
        }
    }

    private static void handleRevokeLike(HttpExchange exchange, Map<String, Object> body) throws IOException {
        int fromId = asInt(body.get("from_id"), -1);
        int toId = asInt(body.get("to_id"), -1);
        synchronized (DB_LOCK) {
            likes.removeIf(l -> asInt(l.get("from_student_id"), -1) == fromId && asInt(l.get("to_student_id"), -1) == toId);
            saveDatabase();
            sendJson(exchange, 200, mapOf("ok", true));
        }
    }

    private static void handleResetSwipes(HttpExchange exchange, Map<String, Object> body) throws IOException {
        int studentId = asInt(body.get("student_id"), -1);
        synchronized (DB_LOCK) {
            likes.removeIf(l -> asInt(l.get("from_student_id"), -1) == studentId);
            saveDatabase();
            sendJson(exchange, 200, mapOf("ok", true));
        }
    }

    private static void handleGetMatches(HttpExchange exchange, int currentId) throws IOException {
        synchronized (DB_LOCK) {
            List<Map<String, Object>> result = new ArrayList<>();
            List<Map<String, Object>> myMatches = new ArrayList<>();
            for (Map<String, Object> m : matches) {
                int a = asInt(m.get("student_a_id"), -1);
                int b = asInt(m.get("student_b_id"), -1);
                if (a == currentId || b == currentId) {
                    myMatches.add(m);
                }
            }
            myMatches.sort((x, y) -> Long.compare(asLong(y.get("created_at"), 0L), asLong(x.get("created_at"), 0L)));

            for (Map<String, Object> m : myMatches) {
                int a = asInt(m.get("student_a_id"), -1);
                int b = asInt(m.get("student_b_id"), -1);
                int partnerId = (a == currentId) ? b : a;
                Map<String, Object> partner = findStudentById(partnerId);
                if (partner == null) continue;

                int matchId = asInt(m.get("id"), -1);
                String lastMsg = null;
                for (Map<String, Object> msg : messages) {
                    if (asInt(msg.get("match_id"), -1) == matchId) {
                        lastMsg = str(msg.get("body"));
                    }
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("match", m);
                item.put("partner", publicStudent(partner));
                item.put("last_message", lastMsg);
                result.add(item);
            }
            sendJson(exchange, 200, result);
        }
    }

    private static void handleUnmatch(HttpExchange exchange, Map<String, Object> body) throws IOException {
        int matchId = asInt(body.get("match_id"), -1);
        int studentId = asInt(body.get("student_id"), -1);
        int partnerId = asInt(body.get("partner_id"), -1);
        synchronized (DB_LOCK) {
            messages.removeIf(msg -> asInt(msg.get("match_id"), -1) == matchId);
            matches.removeIf(m -> asInt(m.get("id"), -1) == matchId);
            likes.removeIf(l -> asInt(l.get("from_student_id"), -1) == studentId && asInt(l.get("to_student_id"), -1) == partnerId);
            saveDatabase();
            sendJson(exchange, 200, mapOf("ok", true));
        }
    }

    private static void handleGetMessages(HttpExchange exchange, int matchId) throws IOException {
        synchronized (DB_LOCK) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Map<String, Object> msg : messages) {
                if (asInt(msg.get("match_id"), -1) == matchId) {
                    result.add(msg);
                }
            }
            result.sort(Comparator.comparingLong(m -> asLong(m.get("sent_at"), 0L)));
            sendJson(exchange, 200, result);
        }
    }

    private static void handleSendMessage(HttpExchange exchange, Map<String, Object> body) throws IOException {
        int matchId = asInt(body.get("match_id"), -1);
        int senderId = asInt(body.get("sender_id"), -1);
        String text = str(body.get("body")).trim();
        if (text.isEmpty()) {
            sendJson(exchange, 400, mapOf("error", "Empty message"));
            return;
        }

        synchronized (DB_LOCK) {
            long now = System.currentTimeMillis();
            messages.add(mapOf(
                "id", nextMessageId++,
                "match_id", matchId,
                "sender_id", senderId,
                "body", text,
                "sent_at", now
            ));

            // Если собеседник — тестовый бот (а не реальный друг), отвечаем на первое сообщение
            Map<String, Object> matchObj = findMatchById(matchId);
            if (matchObj != null) {
                int a = asInt(matchObj.get("student_a_id"), -1);
                int b = asInt(matchObj.get("student_b_id"), -1);
                int partnerId = (a == senderId) ? b : a;
                Map<String, Object> partner = findStudentById(partnerId);
                if (partner != null && DEMO_BOT_EMAILS.contains(str(partner.get("email")))) {
                    long myCount = messages.stream()
                        .filter(m -> asInt(m.get("match_id"), -1) == matchId && asInt(m.get("sender_id"), -1) == senderId)
                        .count();
                    if (myCount == 1) {
                        messages.add(mapOf(
                            "id", nextMessageId++,
                            "match_id", matchId,
                            "sender_id", partnerId,
                            "body", "Класс! Рад знакомству, давай пообщаемся подробнее! ✨",
                            "sent_at", now + 300
                        ));
                    }
                }
            }

            saveDatabase();
            sendJson(exchange, 200, mapOf("ok", true));
        }
    }

    private static void handleReport(HttpExchange exchange, Map<String, Object> body) throws IOException {
        int reporterId = asInt(body.get("reporter_id"), -1);
        int reportedId = asInt(body.get("reported_id"), -1);
        String reason = str(body.get("reason"));

        synchronized (DB_LOCK) {
            reports.add(mapOf(
                "id", nextReportId++,
                "reporter_id", reporterId,
                "reported_student_id", reportedId,
                "reason", reason,
                "is_blocked", true,
                "created_at", System.currentTimeMillis()
            ));
            likes.removeIf(l ->
                (asInt(l.get("from_student_id"), -1) == reporterId && asInt(l.get("to_student_id"), -1) == reportedId) ||
                (asInt(l.get("from_student_id"), -1) == reportedId && asInt(l.get("to_student_id"), -1) == reporterId)
            );
            Map<String, Object> existingMatch = findExistingMatch(reporterId, reportedId);
            if (existingMatch != null) {
                int matchId = asInt(existingMatch.get("id"), -1);
                messages.removeIf(msg -> asInt(msg.get("match_id"), -1) == matchId);
                matches.removeIf(m -> asInt(m.get("id"), -1) == matchId);
            }
            saveDatabase();
            sendJson(exchange, 200, mapOf("ok", true));
        }
    }

    // ======================= PERSISTENCE & SEED =======================

    private static void loadOrSeedDatabase() throws IOException {
        if (Files.exists(DB_FILE)) {
            String raw = Files.readString(DB_FILE, StandardCharsets.UTF_8);
            if (!raw.isBlank()) {
                Map<String, Object> root = asMap(SimpleJson.parse(raw));
                nextStudentId = asInt(root.get("nextStudentId"), 1);
                nextLikeId = asInt(root.get("nextLikeId"), 1);
                nextMatchId = asInt(root.get("nextMatchId"), 1);
                nextMessageId = asInt(root.get("nextMessageId"), 1);
                nextReportId = asInt(root.get("nextReportId"), 1);

                loadList(root.get("students"), students);
                loadList(root.get("likes"), likes);
                loadList(root.get("matches"), matches);
                loadList(root.get("messages"), messages);
                loadList(root.get("reports"), reports);
                return;
            }
        }

        String passHash = hashPassword("password123");
        int annaId = nextStudentId++;
        students.add(createStudentMap(annaId, "anna@itmoscow.pro", passHash, "Анна Иванова",
            "Информационные технологии", "ИС-21", 18,
            "Увлекаюсь иллюстрацией, читаю книги и пишу код на Kotlin",
            "Рисование, Чтение, Музыка", "relationship", null, 42));

        int alexId = nextStudentId++;
        students.add(createStudentMap(alexId, "alex@itmoscow.pro", passHash, "Александр",
            "Дизайн и искусство", "ДЗ-11", 17,
            "Люблю рисовать и читать",
            "Рисование, Чтение, Фотография", "relationship", null, 58));

        int bobId = nextStudentId++;
        students.add(createStudentMap(bobId, "bob@itmoscow.pro", passHash, "Борис",
            "Информационные технологии", "ИС-22", 19,
            "Играю в баскетбол, смотрю кино и люблю путешествия",
            "Спорт, Кино, Игры, Чтение", "relationship", null, 36));

        int claraId = nextStudentId++;
        students.add(createStudentMap(claraId, "clara@itmoscow.pro", passHash, "Клара",
            "Дизайн и медиа", "ДЗ-21", 18,
            "Фотографирую на плёнку, рисую скетчи и слушаю инди-музыку",
            "Рисование, Фотография, Музыка", "friends", null, 49));

        int denisId = nextStudentId++;
        students.add(createStudentMap(denisId, "denis@itmoscow.pro", passHash, "Денис",
            "Информационные технологии", "ПИ-23", 20,
            "Играю на гитаре, люблю настолки и вкусную кулинарию",
            "Музыка, Игры, Кулинария, Чтение", "friends", null, 31));

        int elenaId = nextStudentId++;
        students.add(createStudentMap(elenaId, "elena@itmoscow.pro", passHash, "Елена",
            "Разработка и дизайн", "РД-21", 18,
            "Обожаю книги, уютные кофейни и поездки за город",
            "Чтение, Путешествия, Кулинария", "relationship", null, 44));

        addLikeInternal(alexId, annaId, true);
        addLikeInternal(bobId, annaId, true);
        addLikeInternal(claraId, annaId, true);
        addLikeInternal(denisId, annaId, true);

        saveDatabase();
    }

    private static void saveDatabase() throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("nextStudentId", nextStudentId);
        root.put("nextLikeId", nextLikeId);
        root.put("nextMatchId", nextMatchId);
        root.put("nextMessageId", nextMessageId);
        root.put("nextReportId", nextReportId);
        root.put("students", students);
        root.put("likes", likes);
        root.put("matches", matches);
        root.put("messages", messages);
        root.put("reports", reports);
        Files.writeString(DB_FILE, SimpleJson.stringify(root), StandardCharsets.UTF_8);
    }

    private static String saveAvatarIfPresent(int studentId, String photoBase64) {
        if (photoBase64 == null || photoBase64.isBlank()) return null;
        try {
            byte[] bytes = Base64.getDecoder().decode(photoBase64);
            String fileName = "user_" + studentId + "_" + System.currentTimeMillis() + ".jpg";
            Files.write(AVATARS_DIR.resolve(fileName), bytes);
            return "/api/avatar/" + fileName;
        } catch (Exception e) {
            return null;
        }
    }

    // ======================= HELPERS =======================

    private static boolean isCollegeEmail(String email) {
        if (email == null) return false;
        int at = email.indexOf('@');
        return at > 0 && email.endsWith(COLLEGE_DOMAIN);
    }

    private static String hashPassword(String password) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(password.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Map<String, Object> createStudentMap(
        int id, String email, String passHash, String fullName,
        String faculty, String studyGroup, int age, String bio,
        String interests, String lookingFor, String photoUri, int likesReceived
    ) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("email", email);
        map.put("password_hash", passHash);
        map.put("full_name", fullName);
        map.put("faculty", faculty);
        map.put("study_group", studyGroup);
        map.put("age", age);
        map.put("bio", bio);
        map.put("interests", interests);
        map.put("looking_for", lookingFor);
        map.put("photo_uri", photoUri);
        map.put("email_verified", true);
        map.put("likes_received", likesReceived);
        map.put("created_at", System.currentTimeMillis());
        return map;
    }

    private static Map<String, Object> publicStudent(Map<String, Object> st) {
        Map<String, Object> copy = new LinkedHashMap<>(st);
        copy.remove("password_hash");
        return copy;
    }

    private static Map<String, Object> findStudentByEmail(String email) {
        for (Map<String, Object> st : students) {
            if (email.equalsIgnoreCase(str(st.get("email")))) return st;
        }
        return null;
    }

    private static Map<String, Object> findStudentById(int id) {
        for (Map<String, Object> st : students) {
            if (asInt(st.get("id"), -1) == id) return st;
        }
        return null;
    }

    private static Map<String, Object> findMatchById(int id) {
        for (Map<String, Object> m : matches) {
            if (asInt(m.get("id"), -1) == id) return m;
        }
        return null;
    }

    private static Map<String, Object> findExistingMatch(int id1, int id2) {
        for (Map<String, Object> m : matches) {
            int a = asInt(m.get("student_a_id"), -1);
            int b = asInt(m.get("student_b_id"), -1);
            if ((a == id1 && b == id2) || (a == id2 && b == id1)) return m;
        }
        return null;
    }

    private static boolean hasPositiveLike(int fromId, int toId) {
        for (Map<String, Object> l : likes) {
            if (asInt(l.get("from_student_id"), -1) == fromId
                && asInt(l.get("to_student_id"), -1) == toId
                && Boolean.TRUE.equals(l.get("is_like"))) {
                return true;
            }
        }
        return false;
    }

    private static void addLikeInternal(int fromId, int toId, boolean isLike) {
        likes.add(mapOf(
            "id", nextLikeId++,
            "from_student_id", fromId,
            "to_student_id", toId,
            "is_like", isLike,
            "created_at", System.currentTimeMillis()
        ));
    }

    private static Set<Integer> getBlockedIdsFor(int currentId) {
        Set<Integer> blocked = new HashSet<>();
        for (Map<String, Object> r : reports) {
            if (asInt(r.get("reporter_id"), -1) == currentId && Boolean.TRUE.equals(r.get("is_blocked"))) {
                blocked.add(asInt(r.get("reported_student_id"), -1));
            }
        }
        return blocked;
    }

    private static void loadList(Object rawList, List<Map<String, Object>> dest) {
        dest.clear();
        if (rawList instanceof List<?>) {
            for (Object item : (List<?>) rawList) {
                if (item instanceof Map<?, ?>) {
                    dest.add(asMap(item));
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object obj) {
        Map<String, Object> res = new LinkedHashMap<>();
        if (obj instanceof Map<?, ?>) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) obj).entrySet()) {
                res.put(String.valueOf(e.getKey()), e.getValue());
            }
        }
        return res;
    }

    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static int asInt(Object o, int def) {
        if (o instanceof Number) return ((Number) o).intValue();
        try {
            return o == null ? def : Integer.parseInt(String.valueOf(o));
        } catch (Exception e) {
            return def;
        }
    }

    private static long asLong(Object o, long def) {
        if (o instanceof Number) return ((Number) o).longValue();
        try {
            return o == null ? def : Long.parseLong(String.valueOf(o));
        } catch (Exception e) {
            return def;
        }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static int parseInt(String s, int def) {
        try {
            return s == null ? def : Integer.parseInt(s);
        } catch (Exception e) {
            return def;
        }
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null || query.isEmpty()) return map;
        for (String pair : query.split("&")) {
            int idx = pair.indexOf('=');
            if (idx > 0) {
                String k = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
                String v = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
                map.put(k, v);
            }
        }
        return map;
    }

    private static String readBody(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int r;
        while ((r = is.read(buf)) != -1) {
            baos.write(buf, 0, r);
        }
        return baos.toString(StandardCharsets.UTF_8);
    }

    private static void sendJson(HttpExchange exchange, int status, Object payload) throws IOException {
        byte[] bytes = SimpleJson.stringify(payload).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    // ======================= МИНИ-ПАРСЕР И СЕРИАЛИЗАТОР JSON =======================

    static class SimpleJson {
        private final String s;
        private int pos = 0;

        SimpleJson(String s) {
            this.s = s;
        }

        static Object parse(String json) {
            return new SimpleJson(json).readValue();
        }

        static String stringify(Object obj) {
            StringBuilder sb = new StringBuilder();
            writeValue(obj, sb);
            return sb.toString();
        }

        private static void writeValue(Object obj, StringBuilder sb) {
            if (obj == null) {
                sb.append("null");
            } else if (obj instanceof Boolean || obj instanceof Number) {
                sb.append(obj);
            } else if (obj instanceof Map<?, ?>) {
                sb.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : ((Map<?, ?>) obj).entrySet()) {
                    if (!first) sb.append(',');
                    first = false;
                    writeString(String.valueOf(e.getKey()), sb);
                    sb.append(':');
                    writeValue(e.getValue(), sb);
                }
                sb.append('}');
            } else if (obj instanceof Collection<?>) {
                sb.append('[');
                boolean first = true;
                for (Object item : (Collection<?>) obj) {
                    if (!first) sb.append(',');
                    first = false;
                    writeValue(item, sb);
                }
                sb.append(']');
            } else {
                writeString(String.valueOf(obj), sb);
            }
        }

        private static void writeString(String str, StringBuilder sb) {
            sb.append('"');
            for (int i = 0; i < str.length(); i++) {
                char c = str.charAt(i);
                switch (c) {
                    case '"': sb.append("\\\""); break;
                    case '\\': sb.append("\\\\"); break;
                    case '\b': sb.append("\\b"); break;
                    case '\f': sb.append("\\f"); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    case '\t': sb.append("\\t"); break;
                    default:
                        if (c < 0x20) {
                            sb.append(String.format("\\u%04x", (int) c));
                        } else {
                            sb.append(c);
                        }
                }
            }
            sb.append('"');
        }

        private void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }

        private Object readValue() {
            skipWs();
            if (pos >= s.length()) return null;
            char c = s.charAt(pos);
            if (c == '{') return readObject();
            if (c == '[') return readArray();
            if (c == '"') return readString();
            if (c == 't') { pos += 4; return Boolean.TRUE; }
            if (c == 'f') { pos += 5; return Boolean.FALSE; }
            if (c == 'n') { pos += 4; return null; }
            return readNumber();
        }

        private Map<String, Object> readObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++; // '{'
            skipWs();
            if (pos < s.length() && s.charAt(pos) == '}') { pos++; return map; }
            while (pos < s.length()) {
                skipWs();
                String key = readString();
                skipWs();
                if (pos < s.length() && s.charAt(pos) == ':') pos++;
                Object val = readValue();
                map.put(key, val);
                skipWs();
                if (pos < s.length() && s.charAt(pos) == ',') {
                    pos++;
                } else {
                    if (pos < s.length() && s.charAt(pos) == '}') pos++;
                    break;
                }
            }
            return map;
        }

        private List<Object> readArray() {
            List<Object> list = new ArrayList<>();
            pos++; // '['
            skipWs();
            if (pos < s.length() && s.charAt(pos) == ']') { pos++; return list; }
            while (pos < s.length()) {
                list.add(readValue());
                skipWs();
                if (pos < s.length() && s.charAt(pos) == ',') {
                    pos++;
                } else {
                    if (pos < s.length() && s.charAt(pos) == ']') pos++;
                    break;
                }
            }
            return list;
        }

        private String readString() {
            pos++; // '"'
            StringBuilder sb = new StringBuilder();
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\' && pos < s.length()) {
                    char esc = s.charAt(pos++);
                    switch (esc) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            if (pos + 4 <= s.length()) {
                                sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                                pos += 4;
                            }
                            break;
                        default: sb.append(esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        private Number readNumber() {
            int start = pos;
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (Character.isDigit(c) || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
                    pos++;
                } else {
                    break;
                }
            }
            String num = s.substring(start, pos);
            if (num.contains(".") || num.contains("e") || num.contains("E")) {
                return Double.parseDouble(num);
            }
            return Long.parseLong(num);
        }
    }
}
