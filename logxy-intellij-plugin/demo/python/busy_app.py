"""Logs a busy web shop's backend with Python's logging, for logxy's "Python logging" parser.

It starts with ten minutes of backdated history, so the time histogram has a shape at once, then logs live; an
incident with retries and errors comes now and then, its errors with a traceback. Like most Python services, it logs
to stderr.
"""

import logging
import random
import time

HISTORY_SECONDS = 10 * 60
INCIDENT_EVERY_SECONDS = 90
INCIDENT_SECONDS = 8

rng = random.Random(42)
now = time.time()


def backdated(factory):
    """Stamps each record with the simulated time instead of the wall clock."""

    def make(*args, **kwargs):
        record = factory(*args, **kwargs)
        record.created = now
        record.msecs = (now * 1000) % 1000
        return record

    return make


def incident():
    return now % INCIDENT_EVERY_SECONDS < INCIDENT_SECONDS


def millis(typical):
    """About typical milliseconds, now and then much more."""
    return typical // 2 + rng.randrange(typical) + (typical * 10 if rng.randrange(20) == 0 else 0)


def startup():
    log = logging.getLogger("shop.app")
    log.info("Starting shop backend on Python with 4 workers")
    logging.getLogger("waitress").info("Serving on http://0.0.0.0:8080")
    logging.getLogger("shop.db").info("Connection pool ready with 10 connections")
    log.info("Started in 1.87 seconds")


def inventory_timeout(order):
    try:
        raise TimeoutError(f"connect to inventory-service timed out after 5.0 s (order {order})")
    except TimeoutError:
        logging.getLogger("shop.inventory.client").exception("Request to inventory-service failed for order %d", order)


def record():
    order = 310000 + rng.randrange(9000)
    if incident() and rng.randrange(3) == 0:
        if rng.randrange(3) == 0:
            inventory_timeout(order)
        else:
            logging.getLogger("shop.inventory.client").warning(
                "Retrying call to inventory-service, attempt %d of 3", 1 + rng.randrange(3))
        return
    kind = rng.randrange(12)
    if kind < 3:
        logging.getLogger("shop.orders.api").info("GET /api/v1/orders/%d completed in %d ms", order, millis(40))
    elif kind < 5:
        logging.getLogger("shop.orders.repository").debug(
            "Fetched %d rows from orders in %d ms", 1 + rng.randrange(50), millis(8))
    elif kind == 5:
        logging.getLogger("shop.payments").info(
            "Payment %d authorized for %d.00 EUR", 880000 + rng.randrange(9999), 5 + rng.randrange(400))
    elif kind == 6:
        logging.getLogger("shop.shipping.consumer").info(
            "Consumed %d events from topic shipments, lag %d", 1 + rng.randrange(200), rng.randrange(40))
    elif kind == 7:
        logging.getLogger("shop.cache").debug(
            "Cache hit ratio %d%% over the last %d lookups", 80 + rng.randrange(20), 500 + rng.randrange(1500))
    elif kind == 8:
        logging.getLogger("shop.sessions").info("Removed %d expired sessions", rng.randrange(120))
    elif kind == 9:
        logging.getLogger("shop.auth").info(
            "Issued access token for user %d, expires in 3600 s", 1000 + rng.randrange(9000))
    elif kind == 10:
        if rng.randrange(4) == 0:
            logging.getLogger("shop.billing.repository").warning(
                "Slow query on invoices took %d ms", 800 + rng.randrange(2400))
        else:
            logging.getLogger("shop.orders.api").debug("Validated order %d", order)
    elif rng.randrange(8) == 0:
        logging.getLogger("shop.payments").error("Payment declined for order %d: insufficient funds", order)
    else:
        logging.getLogger("shop.cart").info(
            "Cart %d checked out with %d items", 50000 + rng.randrange(9999), 1 + rng.randrange(9))


def main():
    global now
    logging.setLogRecordFactory(backdated(logging.getLogRecordFactory()))
    logging.basicConfig(level=logging.DEBUG, format="%(asctime)s - %(name)s - %(levelname)s - %(message)s")
    live = now
    now -= HISTORY_SECONDS
    startup()
    while now < live:
        record()
        now += rng.randrange(60 if incident() else 400) / 1000
    while True:
        time.sleep(rng.randrange(40 if incident() else 250) / 1000)
        now = time.time()
        record()


if __name__ == "__main__":
    main()
