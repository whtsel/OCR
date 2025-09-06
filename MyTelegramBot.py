import logging
import numpy as np
from telegram import Update, File
from telegram.ext import Application, CommandHandler, MessageHandler, filters, ContextTypes
from telegram.constants import ChatMemberStatus
from PIL import Image
import easyocr
from io import BytesIO
import fitz # PyMuPDF
import docx
import os
from textwrap import wrap # For splitting long messages
from datetime import datetime, timedelta

# --- Config ---
BOT_TOKEN = "7591495663:AAEuzJjo2EaqMnK0Up8qzUYOUtQ9ZjFQLro"
REQUIRED_GROUP_ID = -1007887557498
BOT_NAME = "Selorm"

# Webhook configuration
PORT = int(os.environ.get('PORT', 5000))
WEBHOOK_URL = "YOUR_WEBHOOK_URL"  # Replace with your actual webhook URL

# --- Logging ---
logging.basicConfig(format='%(asctime)s - %(levelname)s - %(message)s', level=logging.INFO)
logger = logging.getLogger(__name__)

# --- Storage ---
user_group_status = {}
user_credits = {}  # {user_id: credits}
last_credit_reset = {} # {user_id: datetime}
reader = easyocr.Reader(['en'], gpu=False)

# --- Group Check ---
async def is_user_member_of_required_group(user_id: int, bot, chat_type: str) -> bool:
    if chat_type == "private":
        return True
    if user_group_status.get(user_id):
        return True
    try:
        member = await bot.get_chat_member(REQUIRED_GROUP_ID, user_id)
        if member.status in [ChatMemberStatus.MEMBER, ChatMemberStatus.ADMINISTRATOR, ChatMemberStatus.OWNER]:
            user_group_status[user_id] = True
            return True
        return False
    except Exception as e:
        logger.warning(f"Group check failed: {e}")
        return False

# --- Text Extraction ---
def extract_text_from_image(image_bytes: bytes) -> str:
    image = Image.open(BytesIO(image_bytes)).convert("RGB")
    image_np = np.array(image)
    result = reader.readtext(image_np, detail=0)
    return "\n".join(result)

def extract_text_from_pdf(pdf_bytes: bytes) -> str:
    text = ""
    with fitz.open(stream=pdf_bytes, filetype="pdf") as doc:
        for page in doc:
            text += page.get_text()
    return text

def extract_text_from_docx(docx_bytes: bytes) -> str:
    temp_path = "temp.docx"
    with open(temp_path, "wb") as f:
        f.write(docx_bytes)
    doc = docx.Document(temp_path)
    text = "\n".join([para.text for para in doc.paragraphs])
    os.remove(temp_path)
    return text

def extract_text_from_txt(txt_bytes: bytes) -> str:
    return txt_bytes.decode("utf-8", errors="ignore")

# --- Credits ---
def get_user_credits(user_id: int) -> int:
    now = datetime.now()
    if user_id not in last_credit_reset or (now - last_credit_reset[user_id]) > timedelta(hours=24):
        user_credits[user_id] = 6
        last_credit_reset[user_id] = now
    return user_credits.get(user_id, 0)

def deduct_credit(user_id: int):
    user_credits[user_id] = max(0, get_user_credits(user_id) - 1)

def add_credits(user_id: int, amount: int):
    user_credits[user_id] = get_user_credits(user_id) + amount

# --- Commands ---
async def start(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    user = update.effective_user
    chat_type = update.effective_chat.type
    await update.message.reply_html(
        f"Hi {user.mention_html()}!  I'm <b>{BOT_NAME}</b>. Send me a file and I’ll extract text from it.\n"
        f"  Credits remaining: <b>{get_user_credits(user.id)}</b>"
    )

    if not await is_user_member_of_required_group(user.id, context.bot, chat_type):
        await update.message.reply_text("  Please join our group:\nhttps://t.me/+BGbViTLM8sg3ZmM0")

async def redeem(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    user_id = update.effective_user.id
    try:
        code = context.args[0]
        if code == "FREE5":
            add_credits(user_id, 5)
            await update.message.reply_text("  Code accepted. You now have more credits.")
        else:
            await update.message.reply_text("  Invalid code.")
    except IndexError:
        await update.message.reply_text("Usage: /redeem YOURCODE")

# --- Main Message Handler ---
async def handle_messages(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    user = update.effective_user
    message = update.message
    chat_type = update.effective_chat.type

    if not message or not message.document and not message.photo:
        await message.reply_text("  Please send an image, PDF, Word, or text file.")
        return

    if not await is_user_member_of_required_group(user.id, context.bot, chat_type):
        await message.reply_text("  Join our group to continue:\nhttps://t.me/+BGbViTLM8sg3ZmM0")
        return

    if get_user_credits(user.id) <= 0:
        await message.reply_text(
            "  You're out of credits!\n  Visit our site, watch an ad, get a code, and send:\n"
            "/redeem YOURCODE"
        )
        return

    file_id = None
    mime_type = None

    if message.photo:
        file_id = message.photo[-1].file_id
        mime_type = "image/jpeg"
    elif message.document:
        file_id = message.document.file_id
        mime_type = message.document.mime_type

    if not file_id:
        await message.reply_text("  Couldn’t find a file.")
        return

    await message.reply_text("  Scanning your file...")

    try:
        telegram_file: File = await context.bot.get_file(file_id)
        file_bytes = await telegram_file.download_as_bytearray()

        if mime_type.startswith("image/"):
            extracted = extract_text_from_image(file_bytes)
        elif mime_type == "application/pdf":
            extracted = extract_text_from_pdf(file_bytes)
        elif mime_type == "application/vnd.openxmlformats-officedocument.wordprocessingml.document":
            extracted = extract_text_from_docx(file_bytes)
        elif mime_type == "text/plain":
            extracted = extract_text_from_txt(file_bytes)
        else:
            await message.reply_text("  File type not supported.")
            return

        deduct_credit(user.id)

        chunks = wrap(extracted, width=4000)
        for i, chunk in enumerate(chunks):
            if i == 0:
                await message.reply_text(f"  Extracted text:\n\n{chunk}")
            else:
                await message.reply_text(chunk)

        await message.reply_text(f"  Remaining credits: {get_user_credits(user.id)}")

    except Exception as e:
        logger.error(f"Error: {e}", exc_info=True)
        await message.reply_text("  Something went wrong while processing your file.")

# --- Main ---
def main():
    application = Application.builder().token(BOT_TOKEN).build()
    application.add_handler(CommandHandler("start", start))
    application.add_handler(CommandHandler("redeem", redeem))
    application.add_handler(MessageHandler(filters.ALL & (~filters.COMMAND), handle_messages))

    # Replaced run_polling with run_webhook
    application.run_webhook(
        listen="0.0.0.0",
        port=PORT,
        url_path=BOT_TOKEN,
        webhook_url=WEBHOOK_URL + '/' + BOT_TOKEN
    )
    logger.info("  Selorm Bot is live using webhooks!")

if __name__ == "__main__":
    main()