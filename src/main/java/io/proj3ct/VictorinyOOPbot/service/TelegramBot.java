package io.proj3ct.VictorinyOOPbot.service;

import io.proj3ct.VictorinyOOPbot.config.BotConfig;
import io.proj3ct.VictorinyOOPbot.model.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;


@Component
public class TelegramBot extends TelegramLongPollingBot {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CategoryService categoryService;

    @Autowired
    private QuestionsService questionsService;

    @Autowired
    private AnswersService answersService;

    @Autowired
    private QuestionsRepository questionsRepository;

    private Long currentCategoryId;

    final BotConfig config;

    public TelegramBot(BotConfig config) {
        this.config = config;
    }

    @Override
    public String getBotUsername() {
        return config.getBotName();
    }

    @Override
    public String getBotToken() {
        return config.getToken();
    }

    private void finishGame(long chatId) {
        askedQuestions.clear();

        sendMessage(chatId, "Игра завершена. Ты ответил правильно на " + correctAnswersCount + " вопросов.", false);
        correctAnswersCount = 0;
        sendCategoryOptions(chatId);
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasCallbackQuery()) {
            String callbackData = update.getCallbackQuery().getData();
            long chatId = update.getCallbackQuery().getMessage().getChatId();

            if (callbackData.startsWith("CATEGORY_")) {
                long categoryId = Long.parseLong(callbackData.split("_")[1]);
                currentCategoryId = categoryId;
                Category selectedCategory = categoryService.getCategoryById(categoryId);

                sendMessage(chatId, "Ты выбрал категорию: " + selectedCategory.getName(), true);  // Показываем кнопку завершения
                sendQuestion(chatId, categoryId);  // Задаем вопрос
            } else if (callbackData.startsWith("ANSWER_")) {
                long answerId = Long.parseLong(callbackData.split("_")[1]);
                checkAnswer(chatId, answerId);  // Проверка ответа
            } else if (callbackData.equals("FINISH_GAME")) {
                finishGame(chatId);
            }
        } else if (update.hasMessage() && update.getMessage().hasText()) {
            String messageText = update.getMessage().getText();
            long chatId = update.getMessage().getChatId();

            if ("Завершить игру".equals(messageText)) {
                finishGame(chatId);
            } else {
                switch (messageText) {
                    case "/start":
                        registerUser(update.getMessage());
                        startCommandReceived(chatId, update.getMessage().getChat().getFirstName());
                        break;
                    case "/categories":
                        sendCategoryOptions(chatId);
                        break;
                    default:
                        sendMessage(chatId, "Invalid request", false);
                }
            }
        }
    }

    private void registerUser(Message msg){
        if(userRepository.findById(msg.getChatId()).isEmpty()){
            var chatId = msg.getChatId();
            var chat = msg.getChat();

            User user = new User();

            user.setChatId(chatId);
            user.setFirstName(chat.getFirstName());
            user.setLastName(chat.getLastName());
            user.setUserName(chat.getUserName());
            user.setRegisteredAt(new Timestamp(System.currentTimeMillis()));

            userRepository.save(user);
        }
    }

    private void startCommandReceived(long chatId, String name) {
        String answer = "Привет, " + name + "! 👋\n" +
                "Чтобы начать, выбери категорию вопросов, которая тебе интересна. " +
                "После этого я буду задавать вопросы, а ты сможешь отвечать на них! 🚀\n" +
                "Удачи!";
        sendMessage(chatId, answer, false);
        sendCategoryOptions(chatId);
    }

    private void sendMessage(long chatId, String textToSend, boolean showFinishButton) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(textToSend);

        List<KeyboardRow> keyboard = new ArrayList<>();
        if (showFinishButton) {
            KeyboardRow row = new KeyboardRow();
            row.add("Завершить игру");
            keyboard.add(row);
        }

        // Устанавливаем клавиатуру, если она нужна
        if (!keyboard.isEmpty()) {
            ReplyKeyboardMarkup replyMarkup = new ReplyKeyboardMarkup();
            replyMarkup.setKeyboard(keyboard);
            replyMarkup.setResizeKeyboard(true);
            replyMarkup.setOneTimeKeyboard(false);
            message.setReplyMarkup(replyMarkup);
        }

        try {
            execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void sendCategoryOptions(long chatId) {
        List<Category> categories = categoryService.getAllCategories();
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText("Выбери категорию:");

        List<List<InlineKeyboardButton>> buttons = new ArrayList<>();
        for (Category category : categories) {
            InlineKeyboardButton button = new InlineKeyboardButton();
            button.setText(category.getName());
            button.setCallbackData("CATEGORY_" + category.getId());

            List<InlineKeyboardButton> row = new ArrayList<>();
            row.add(button);
            buttons.add(row);
        }

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(buttons);
        message.setReplyMarkup(markup);

        try {
            execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private Set<Long> askedQuestions = new HashSet<>();
    private int correctAnswersCount = 0;

    private void sendQuestion(long chatId, long categoryId) {
        List<Questions> questions = questionsRepository.findByCategoryId(categoryId);
        if (questions.isEmpty()) {
            sendMessage(chatId, "В этой категории больше нет вопросов.", false);
            finishGame(chatId);
            return;
        }

        if (askedQuestions.size() == questions.size()) {
            sendMessage(chatId, "Вы ответили на все вопросы! Завершаем игру.", false);
            finishGame(chatId);
            return;
        }

        Questions question = questionsService.getRandomQuestionByCategoryId(categoryId, askedQuestions);

        if (question == null) {
            sendMessage(chatId, "В этой категории больше нет вопросов.", false);
            finishGame(chatId);
            return;
        }

        askedQuestions.add(question.getId());

        System.out.println("Asked questions size: " + askedQuestions.size());
        System.out.println("Total questions in category: " + questions.size());

        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(question.getText());

        List<List<InlineKeyboardButton>> buttons = new ArrayList<>();
        for (Answers answer : question.getAnswers()) {
            InlineKeyboardButton button = new InlineKeyboardButton();
            button.setText(answer.getText());
            button.setCallbackData("ANSWER_" + answer.getId());

            List<InlineKeyboardButton> row = new ArrayList<>();
            row.add(button);
            buttons.add(row);
        }

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(buttons);
        message.setReplyMarkup(markup);

        try {
            execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void checkAnswer(long chatId, long answerId) {
        if (currentCategoryId == null) {
            sendMessage(chatId, "Ошибка! Категория не была выбрана.", false);
            return;
        }

        boolean isCorrect = answersService.isAnswerCorrect(answerId);

        System.out.println("Answer ID: " + answerId + ", isCorrect: " + isCorrect);

        if (isCorrect) {
            correctAnswersCount++;
            System.out.println("Correct answer count: " + correctAnswersCount);
            sendMessage(chatId, "Правильный ответ! 🎉", true);
        } else {
            sendMessage(chatId, "Неправильно. Попробуй ответить на следующий вопрос.", true);
        }

        sendQuestion(chatId, currentCategoryId);
    }

}