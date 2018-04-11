/*
 * Created by amelnikov 10.10.2017
 */


package ru.mvd.sovm.pgu.hotels.service.handling.pgu;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.mvd.sovm.common.storage.remote.IBDFileStorageService;
import ru.mvd.sovm.common.utils.json.JsonUtil;
import ru.mvd.sovm.common.utils.zip.Unzip;
import ru.mvd.sovm.common.utils.zip.ZipValidator;
import ru.mvd.sovm.gu.hotel.root._1_1.EPGURequestType;
import ru.mvd.sovm.pgu.common.StatusCode;
import ru.mvd.sovm.pgu.common.handling.MessageHandler;
import ru.mvd.sovm.pgu.common.message.ErrorMessageToEPGU;
import ru.mvd.sovm.pgu.common.message.json.Attach;
import ru.mvd.sovm.pgu.common.message.json.JsonMessageData;
import ru.mvd.sovm.pgu.common.service.exception.AttachListException;
import ru.mvd.sovm.pgu.common.service.xml.HotelTargetTypeXmlParser;
import ru.mvd.sovm.pgu.common.utils.*;
import ru.mvd.sovm.pgu.hotels.dao.HotelMessageDao;
import ru.mvd.sovm.pgu.hotels.dao.HotelMessagePpotDao;
import ru.mvd.sovm.pgu.hotels.dao.StatusDao;
import ru.mvd.sovm.pgu.hotels.domain.HotelMessage;
import ru.mvd.sovm.pgu.hotels.domain.HotelMessagePpot;
import ru.mvd.sovm.pgu.hotels.domain.Status;
import ru.mvd.sovm.pgu.hotels.logger.LogControlService;
import ru.mvd.sovm.pgu.hotels.service.amqp.ResponseMessageSender;
import ru.mvd.sovm.pgu.hotels.service.amqp.message.UmmsOpiMessage;
import ru.mvd.sovm.pgu.hotels.service.amqp.message.data.Attachment;
import ru.mvd.sovm.pgu.hotels.service.amqp.seek.SeekMessageSender;
import ru.mvd.sovm.pgu.hotels.service.amqp.umms_opi.RequestMessageSender;
import ru.mvd.sovm.pgu.hotels.service.asn1.Asn1ParserServiceImpl;

import javax.xml.bind.JAXBException;
import javax.xml.bind.UnmarshalException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service("requestHandlingService")
public class EPGURequestHandlingServiceImpl implements MessageHandler {

    private final static String HOTELS_IN_OBJ_TYPE = "a9959b68-4ba1-4a9a-9006-09754c92c566";
    private final static String HOTELS_ZIP_OBJ_TYPE = "2aa5748e-cba8-4815-acb9-77ec5b65c3ff";
    private final static String form5XsdPath = "/xsd/hotel-form5.xsd";
    private final static String migCaseXsdPath = "/xsd/migration-staying.xsd";
    private final static String migUnregXsdPath = "/xsd/migration-staying-unreg.xsd";
    private final static String migStayingEditXsdPath = "xsd/migration-staying-edit.xsd";
    private final static String[] xsdArray = new String[]{form5XsdPath, migCaseXsdPath, migUnregXsdPath, migStayingEditXsdPath};
    private final static Pattern PATTERN_SUPPLIER_GID = Pattern.compile("[\\d]{16}");

    private final LogControlService logService;

    @Value("${responseRoutingKey}")
    private String responseRoutingKey;
    @Value("${settings.inn.check.enabled}")
    private String isInnCheckEnabled;
    private EPGURequestType epguRequestType;
    private Path tempDir;

    private Logger log;
    @Autowired
    private RequestMessageSender ummsOpiSender;
    @Autowired
    private HotelTargetTypeXmlParser hotelTargetTypeXmlParser;
    @Autowired
    private HotelMessageXmlValidator hotelMessageXmlValidator;
    @Autowired
    private HotelMessageConverter hotelMessageConverter;
    @Autowired
    private HotelMessageDao hotelMessageDao;
    @Autowired
    private HotelMessagePpotDao hotelMessagePpotDao;
    @Autowired
    private StatusDao statusDao;
    @Autowired
    private HotelMessageTestUtils hotelMessageTestUtils;
    @Autowired
    private IBDFileStorageService hotelRequestIBDStorageService;
    @Autowired
    private ResponseMessageSender responseMessageSender;
    @Autowired
    private SeekMessageSender seekMessageSender;
    @Autowired
    private Asn1ParserServiceImpl asn1ParserService;

    @Autowired
    public EPGURequestHandlingServiceImpl(LogControlService logService) {
        this.logService = logService;
    }

    @Autowired
    public void setLog(Logger log) {
        this.log = log;
    }

    @Override
    public void handle(String message) {

        try {

            log.info("Rabbit message: " + message);

            final String setLevelPrefix = "cmd:loglevel:";
            if (message.startsWith(setLevelPrefix)) {
                final String level_ = message.substring(setLevelPrefix.length()).trim();
                final Level level;

                switch (level_) {
                    case "1":
                        level = Level.OFF;
                        break;
                    case "2":
                        level = Level.FATAL;
                        break;
                    case "3":
                        level = Level.ERROR;
                        break;
                    case "4":
                        level = Level.WARN;
                        break;
                    case "5":
                        level = Level.INFO;
                        break;
                    case "6":
                        level = Level.DEBUG;
                        break;
                    case "7":
                        level = Level.TRACE;
                        break;
                    case "8":
                        level = Level.ALL;
                        break;
                    default:
                        log.error("Указан неподдерживаемый уровень логирования: " + level_);
                        return;
                }

                logService.setLogLevel(level);
                return;
            }

            /**Создание переменных под типы статусов*/
            Status statusNew = statusDao.getStatusByName("new");
            Status statusInProgress = statusDao.getStatusByName("in_progress");
            Status statusInvalidInput = statusDao.getStatusByName("invalidInput");

            /**Создание карты из статусов*/
            HashMap<String, Status> statusHashMap = new HashMap<String, Status>() {{
                put("new", statusNew);
                put("in_progress", statusInProgress);
                put("invalidInput", statusInvalidInput);
            }};

            /**Маппинг входящей строки на класс*/
            JsonMessageData jsonMessageData = (JsonMessageData) new JsonUtil().fromJSON(message, JsonMessageData.class);

            /**Получение из тэга json'а PrimaryContent содержимого в виде строки, которое является xml*/
            String xmlString = jsonMessageData.getPrimaryContent().getPrimary();

            /**Получение uid сообщения*/
            final String SMEVMessageId = jsonMessageData.getUid();

            /**Обработка случая прихода невалидной xml формата EPGURequest. Ничего не сохраняется на стороне приложения,
             * только отправляется ответ в ЕПГУ о некорректном сообщении*/
            try {
                /**Парсинг xml сообщения из тэга PrimaryContent*/
                epguRequestType = hotelTargetTypeXmlParser.parseXml(xmlString);
                log.debug(epguRequestType);
            } catch (UnmarshalException e1) {
                /**Формирование необходимых для отправки ответа набора данных*/
                HotelMessage hotelMessage = new HotelMessage();
                hotelMessage.setOrderId("0");
                hotelMessage.setSMEVMessageId(SMEVMessageId);
                String xmlResponse = HotelMessageReponseHelper.createXml(
                        hotelMessage,
                        StatusCode.REJECT,
                        ErrorMessageToEPGU.COMMENT_MATCH_XML_SCHEMA.getCommentRus()
                );
                /**Создание json'а с ответом внутри*/
                JsonMessageData messageData = HotelMessageReponseHelper.createMessageData(hotelMessage, xmlResponse, null);
                /**Отправка сообщения в ЕПГУ*/
                responseMessageSender.sendMessage(messageData);
                log.error(messageData, e1);
                throw new UnmarshalException("Invalid xlm EPGURequest type. Check the EPGU side.", e1);
            }

            /**Получение списка приаттаченных uid'ов*/
            List<Attach> attachList = jsonMessageData.getPrimaryContent().getAttach();

            /**Первичная проверка входящего сообщения на наличие аттачей и ветвление логики*/
            if (attachList.size() == 0) {
                log.error("There is nothing in attachments, check the EPGU side.");
                throw new AttachListException("There is nothing in attachments, check the EPGU side.");
            } else if (attachList.size() > 1) {
                log.error("There are more than one attachments, check the EPGU side.");
                throw new AttachListException("There are more than one attachments, check the EPGU side.");
            } else {

                /**Флаг корректности входных данных; необходим для остановки
                 * обработки пакетной загрузки в случае обнаружения бракованной xml*/
                boolean isInputDataCorrect = true;

                /**Создание объекта заявки для фиксации прихода корректного json'а*/
                HotelMessage hotelMessage = hotelMessageConverter.fromEPGURequestType(
                        epguRequestType,
                        SMEVMessageId,
                        statusNew);

                /**Запись в БД объекта заявки*/
                hotelMessageDao.insertHotelMessage(hotelMessage);
                log.debug("Сформированный объект hotelMessage перед записью в БД: " + hotelMessage);
                /**Если код поставщика соответствует определённому формату*/
                if (isSupplierGidCorrect(hotelMessage.getSupplierGid())) {

                    /**Взять первый элемент списка аттачей*/
                    Attach attach = attachList.get(0);

                    /**Сохранить uuid аттача, он же uuid архива со вложениями*/
                    String incomeZipUuid = attach.getId();

                    /**Получить файл из хранилища ibd_files
                     * и сохранить его во врменной директории и вернуть путь до файла*/
                    Path p = hotelRequestIBDStorageService.load(incomeZipUuid);

                    /**Первичная валидация полученного на предыдущем шаге архива*/
                    if (new ZipValidator().validate(p)) {

                        /**Если архив прошёл валидацию, то сохранить его uuid в объект*/
                        hotelMessage.setIncomeZipUuid(incomeZipUuid);

                        /**Скорее всего это связывание здесь лишнее*/
                        hotelRequestIBDStorageService.bindFileToExternalSystemObject(
                                incomeZipUuid, SMEVMessageId, HOTELS_ZIP_OBJ_TYPE
                        );

                        /**Получение объекта типа File со списком всех разархивированных файлов*/
                        File outerZip = handleZip(p);

                        /**В зависимости от одиночной или пакетной загрузок ветвится логика*/
                        switch (epguRequestType.getIsPacket()) {

                            case "0":

                                log.info("Я одиночная загрузка!");

                                /**Создание фильтра для получения из набора файлов только attach.xml*/
                                FilenameFilter filenameFilterXmlSingleUploadType = (dir, name) -> name.equalsIgnoreCase("attach.xml");

                                /**Создание массива из одного файла, получен путём применения фильтра из предыдущего шага*/
                                String[] fileListZipSingleUploadType = outerZip.list(filenameFilterXmlSingleUploadType);

                                /**Вызов метода отправки сообщения*/
                                xmlSender(SMEVMessageId, hotelMessage, statusHashMap, isInputDataCorrect, null, fileListZipSingleUploadType);

                                /**Апдейт записи в базе т.к. был добавлен incomeZipUuid*/
                                hotelMessageDao.updateHotelMessage(hotelMessage);

                                break;

                            case "1":

                                log.info("Я пакетная загрузка!");

                                /**Создание массива из набора файлов без фильтрации по типу файлов*/
                                String[] fileListZip = outerZip.list();

                                /**Проверка на есть ли с чем работать*/
                                if (fileListZip != null && fileListZip.length > 0) {

                                    /**Просмотр всего списка имён разархивированных файлов*/
                                    for (String item :
                                            fileListZip) {

                                        /**Если какой-то из файлов валидируется как zip-архив*/
                                        if (new ZipValidator().validate(Paths.get(outerZip.getAbsolutePath() + "/" + item))) {

                                            /**Создать объект Path с путём до zip-архива*/
                                            Path zipPath = Paths.get(tempDir.toString() + "/" + item);

                                            /**Получение объекта типа File со списком всех разархивированных файлов*/
                                            File innerZip = handleZip(zipPath);

                                            /**Создание фильтра для получения из набора файлов только с расширением .xml*/
                                            FilenameFilter filenameFilterXml = (dir, name) -> name.endsWith(".xml");

                                            /**Создание фильтра для получения из набора файлов только с расширением .sig*/
                                            FilenameFilter certificateFilterSig = (dir, name) -> name.endsWith(".sig");

                                            /**Создание массива из одного файла, получен путём применения фильтра из предыдущего шага*/
                                            String[] certificateListSig = outerZip.list(certificateFilterSig);

                                            if (certificateListSig != null && certificateListSig.length != 0) {

                                                /**Создание массива из файлов одного типа, полученных путём применения фильтра из предыдущего шага*/
                                                String[] fileListXml = innerZip.list(filenameFilterXml);

                                                //TODO: завести статус ошибки по таймауту и отправлять в ЕПГУ ошибку обработки данных

                                                /**Вызов метода отправки сообщения; для проверки ИНН берётся любой из сертификатов на позиции certificateListSig[0]
                                                 * т.к. считается, что всё сообщение подписано одним сертификатом, и в каждой подписи содержатся идентичные данные*/
                                                xmlSender(SMEVMessageId, hotelMessage, statusHashMap, isInputDataCorrect, outerZip.getAbsolutePath() + "/" + certificateListSig[0], fileListXml);

                                                /**Апдейт записи в базе т.к. был добавлен incomeZipUuid
                                                 * и произведены изменения методом xmlSender*/
                                                hotelMessageDao.updateHotelMessage(hotelMessage);

                                            } else {

                                                createAndSendMessageToEPGU(
                                                        hotelMessage,
                                                        statusInvalidInput,
                                                        StatusCode.REJECT,
                                                        ErrorMessageToEPGU.COMMENT_NO_DIGITAL_SIGNATURE.getCommentRus(),
                                                        true
                                                );

                                            }
                                        }
                                    }
                                }

                                break;

                            default:
                                log.error(ErrorMessageToEPGU.COMMENT_UNKNOWN_UPLOAD_TYPE.getCommentRus());
                                /**В случае если тип загрузки не 0 и не 1*/
                                createAndSendMessageToEPGU(
                                        hotelMessage,
                                        statusInvalidInput,
                                        StatusCode.REJECT,
                                        ErrorMessageToEPGU.COMMENT_UNKNOWN_UPLOAD_TYPE.getCommentRus(),
                                        true
                                );
                        }
                    } else {
                        log.error(ErrorMessageToEPGU.COMMENT_INVALID_ZIP_FILE.getCommentRus());
                        createAndSendMessageToEPGU(
                                hotelMessage,
                                statusInvalidInput,
                                StatusCode.REJECT,
                                ErrorMessageToEPGU.COMMENT_INVALID_ZIP_FILE.getCommentRus(),
                                true
                        );
                    }
                } else {
                    log.error(ErrorMessageToEPGU.COMMENT_ILLEGAL_ORGANISATION_ID.getCommentRus());
                    createAndSendMessageToEPGU(
                            hotelMessage,
                            statusInvalidInput,
                            StatusCode.REJECT,
                            ErrorMessageToEPGU.COMMENT_ILLEGAL_ORGANISATION_ID.getCommentRus(),
                            true
                    );
                }
            }
        } catch (AttachListException | IOException | JAXBException e) {
            log.error("", e);
        }
    }

    private void createAndSendMessageToEPGU(HotelMessage hm, Status st, StatusCode sc, String emte, boolean dbu) {
        hm.setStatus(st);
        String xmlResponse = HotelMessageReponseHelper.createXml(hm, sc, emte);
        JsonMessageData messageData = HotelMessageReponseHelper.createMessageData(hm, xmlResponse, null);
        responseMessageSender.sendMessage(messageData);
        if (dbu) {
            hotelMessageDao.updateHotelMessage(hm);
        }
    }

    private File handleZip(Object zipFilePath) throws IOException {
        /**Создание метки времени*/
        Timestamp timestamp = new Timestamp(System.currentTimeMillis());
        /**Запись в глобальную переменную типа Path пути до временного хранилища разархивированных данных*/
        tempDir = Files.createTempDirectory(timestamp.toString().replaceAll("[-.: ]", "") + "_");
        /**Разархивация файла, взятого по пути zipFilePath в путь tempDir*/
        Unzip.unzip(zipFilePath.toString(), tempDir.toString());
        /**Возврат объекта типа File со списком всех разархивированных файлов*/
        return new File(tempDir.toString());
    }

    /**
     * Комбинированный метод получения типа xml и результата валидации по схеме xsd
     */
    private HotelMessageXmlProperties getXmlProperties(String xmlPath) throws IOException {
        for (String xsdPath :
                xsdArray) {
            log.debug("Я путь до xsd схемы: " + getClass().getResource(xsdPath).toString());
            if (hotelMessageXmlValidator.validateXML(xmlPath, getClass().getResource(xsdPath).toString())) {
                return new HotelMessageXmlProperties(true, xsdPath);
            }
        }
        return new HotelMessageXmlProperties(false, "");
    }

    private boolean isInnFromXmlValid(String xmlPath, String certificatePath, String xmlType) throws JAXBException, IOException {
        String innXml = "";
        final String innKeyword = "1.2.643.3.131.1.1";

        /**Формирование строки из файла подписи*/
        Set<String> innFromCertificate =  asn1ParserService.getINNsFromCertificate(certificatePath, innKeyword);

        if (xmlType.equals("/xsd/hotel-form5.xsd")) {
            /**Извлечение ИНН из вложенной в архив xml form5*/
            innXml = hotelTargetTypeXmlParser.parseXmlForm5(getXmlFromXmlPath(xmlPath)).getHotelInfo().getOrganization().getInn();
        } else if (xmlType.equals("/xsd/migration-staying.xsd")) {
            /**Извлечение ИНН из вложенной в архив xml migCase*/
            innXml = hotelTargetTypeXmlParser.parseXmlMigCase(getXmlFromXmlPath(xmlPath)).getHost().getOrganization().getInn();
        }
        /**Проверка соответствия ИНН в xml и ИНН в сертификате и возврат результата сравнения*/
        return innFromCertificate.contains(innXml);
    }

    private boolean isSupplierGidEqualsToSupplierInfo(String supplierGid, String xmlPath, String xmlType) throws IOException, JAXBException {
        if (xmlType.equals("/xsd/hotel-form5.xsd")) {
            return supplierGid.equals(hotelTargetTypeXmlParser.parseXmlForm5(getXmlFromXmlPath(xmlPath)).getSupplierInfo());
        } else if (xmlType.equals("/xsd/migration-staying.xsd")) {
            return supplierGid.equals(hotelTargetTypeXmlParser.parseXmlMigCase(getXmlFromXmlPath(xmlPath)).getSupplierInfo());
        } else if (xmlType.equals("/xsd/migration-staying-unreg.xsd")) {
            return supplierGid.equals(hotelTargetTypeXmlParser.parseXmlUnreg(getXmlFromXmlPath(xmlPath)).getSupplierInfo());
        }
        return false;
    }

    private String getXmlFromXmlPath(String s) throws IOException {
        return new String(getXmlWithNoBOM(Files.readAllBytes(Paths.get(s))), "UTF-8");
    }

    private void xmlSender(
            String SMEVMessageId,
            HotelMessage hotelMessage,
            HashMap<String, Status> statusHashMap,
            boolean isInputDataCorrect,
            String certificate,
            String... xmlList

    ) throws IOException, JAXBException {

        /**Если список xml не пуст и содержит хотя бы один элемент*/
        if (xmlList != null && xmlList.length > 0) {

            /**Просмотр списка всех отправляемых xml*/
            for (String aFileListXml : xmlList) {
                String xmlPath = tempDir.toString() + "/" + aFileListXml;
                /**Создание объекта со свойствами xml: валидность и тип*/
                HotelMessageXmlProperties xmlProperties = getXmlProperties(xmlPath);
                /**Если xml не проходит валидацию по одной из схем*/
                if (!xmlProperties.isValide()) {
                    hotelMessage.setStatus(statusHashMap.get("invalidInput"));
                    String xmlResponse = HotelMessageReponseHelper.createXml(
                            hotelMessage,
                            StatusCode.REJECT,
                            ErrorMessageToEPGU.COMMENT_MATCH_XML_SCHEMA.getCommentRus()
                    );
                    JsonMessageData messageData = HotelMessageReponseHelper.createMessageData(hotelMessage, xmlResponse, null);
                    /**Отправка сообщения в ЕПГУ*/
                    responseMessageSender.sendMessage(messageData);

                    isInputDataCorrect = false;
                    break;
                }

                String xmlType = xmlProperties.getXmlType();

                /**Если в настройках включена проверка ИНН
                 * И если это пакетная загрузка
                 * И если это не снятие с регистрации*/
                if (isInnCheckEnabled.equals("t") &&
                        certificate != null &&
                        !xmlType.equals("/xsd/migration-staying-unreg.xsd")
                        ) {
                    /**Проверка соответствия ИНН в xml ИНН в сертификате*/
                    if (!isInnFromXmlValid(xmlPath, certificate, xmlType)) {
                        createAndSendMessageToEPGU(
                                hotelMessage,
                                statusHashMap.get("invalidInput"),
                                StatusCode.REJECT,
                                ErrorMessageToEPGU.COMMENT_ILLEGAL_INN.getCommentRus(),
                                false
                        );

                        isInputDataCorrect = false;
                        break;
                    }
                }

                /**Проверка соответствия значения тэга <supplierInfo> из xml значению тэга <idOrganization> из запроса*/
                if (!isSupplierGidEqualsToSupplierInfo(hotelMessage.getSupplierGid(), xmlPath, xmlType)) {
                    createAndSendMessageToEPGU(
                            hotelMessage,
                            statusHashMap.get("invalidInput"),
                            StatusCode.REJECT,
                            ErrorMessageToEPGU.COMMENT_ILLEGAL_SUPPLIER_INFO.getCommentRus(),
                            false
                    );

                    isInputDataCorrect = false;
                    break;
                }

            }

            /**Если все xml прошли проверку*/
            if (isInputDataCorrect) {

                for (String aFileListXml : xmlList) {
                    /**Формирование пути до xml*/
                    String xmlPath = tempDir.toString() + "/" + aFileListXml;
                    /**Получение содержимого xml файла*/
                    String xmlContent = hotelMessageTestUtils.getFileContent(new FileInputStream(new File(xmlPath)), "UTF-8");
                    /**Подготовка служебных данных*/
                    UUID ummsMessageUuid_ = UUID.randomUUID();
                    /**Формирование объекта перед отправкой в ППОТ*/
                    final String ummsMessageUuid = ummsMessageUuid_.toString();
                    HotelMessagePpot hotelMessagePpot = new HotelMessagePpot(
                            hotelMessage,
                            xmlContent,
                            aFileListXml,
                            statusHashMap.get("new"),
                            ummsMessageUuid
                    );
                    /**Сохранение в БД сформированного объекта*/
                    hotelMessagePpotDao.insertHotelMessagePpot(hotelMessagePpot);
                    /**Формирование списка аттачей*/
                    Attachment attachment = new Attachment();
                    attachment.setType("application/xml");
                    attachment.setName(aFileListXml);
                    attachment.setContent(xmlContent);

                    String applicationId = "hotel";
                    /**Отправка сообщения с xml в коннектор*/
                    log.debug("Отправляем в коннектор: SMEVMessageId=" + SMEVMessageId + ", ummsMessageUuid=" + ummsMessageUuid);
                    ummsOpiSender.sendMessage(
                            UmmsOpiMessage.createRequestMessage(
                                    SMEVMessageId,
                                    ummsMessageUuid,
                                    applicationId,
                                    attachment,
                                    responseRoutingKey,
                                    hotelMessage.getSupplierGid()
                            ));
                    /**Отправка сообщения с xml в Розыск*/
                    log.debug("Отправляем в пнр SMEVMessageId=" + SMEVMessageId + ", ummsMessageUuid=" + ummsMessageUuid);
                    seekMessageSender.sendMessage(
                            UmmsOpiMessage.createRequestMessage(
                                    SMEVMessageId,
                                    ummsMessageUuid,
                                    applicationId,
                                    attachment,
                                    responseRoutingKey,
                                    hotelMessage.getSupplierGid()
                            ));
                    /**Изменения статуса заявки на "в процессе"*/
                    hotelMessagePpot.setStatus(statusHashMap.get("in_progress"));
                    /**Запись изменения статуса в таблицу hotelMessagePpot*/
                    hotelMessagePpotDao.updateHotelMessagePpot(hotelMessagePpot);
                }
                /**Запись изменения статуса в таблицу hotelMessage*/
                hotelMessage.setStatus(statusHashMap.get("in_progress"));
            }

        } else {
            hotelMessage.setStatus(statusHashMap.get("invalidInput"));
            String xmlResponse = HotelMessageReponseHelper.createXml(hotelMessage, StatusCode.REJECT, ErrorMessageToEPGU.COMMENT_EMPTY_XML_LIST.getCommentRus());
            JsonMessageData messageData = HotelMessageReponseHelper.createMessageData(hotelMessage, xmlResponse, null);
            /**Отправка сообщения в ЕПГУ*/
            responseMessageSender.sendMessage(messageData);
        }
    }

    /**
     * Метод проверки соответствия кода поставщика определённомму формату
     */
    private boolean isSupplierGidCorrect(String supplierGid) {
        if (supplierGid == null) {
            return false;
        } else {
            Matcher m = PATTERN_SUPPLIER_GID.matcher(supplierGid);
            return m.matches();
        }
    }

    private boolean isBOMInXml(byte[] ba) {
        return (ba[0] & 0xFF) == 0xEF &&
                (ba[1] & 0xFF) == 0xBB &&
                (ba[2] & 0xFF) == 0xBF;
    }

    private byte[] getXmlWithNoBOM(byte[] ba) {
        if (isBOMInXml(ba)) {
            for (int i = 0; i < 3; i++) {
                ba = ArrayUtils.removeElement(ba, ba[0]);
            }
        }
        return ba;
    }
}
