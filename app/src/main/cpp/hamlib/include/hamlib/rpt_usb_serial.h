/*
 *  Hamlib Interface - Android USB serial bridge, host callback table
 *
 *  The serial port hook for "android-usb:<deviceId>:<portIndex>" paths is
 *  based on the Android USB serial bridge from JS8Android, used under the
 *  LGPL with its author's permission.
 *
 *   This library is free software; you can redistribute it and/or
 *   modify it under the terms of the GNU Lesser General Public
 *   License as published by the Free Software Foundation; either
 *   version 2.1 of the License, or (at your option) any later version.
 *
 *   This library is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *   Lesser General Public License for more details.
 *
 *   You should have received a copy of the GNU Lesser General Public
 *   License along with this library; if not, write to the Free Software
 *   Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 *
 */

#ifndef _RPT_USB_SERIAL_H
#define _RPT_USB_SERIAL_H 1

#include <hamlib/rig_dll.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Android has no /dev/ttyUSB* for an app to open, so the host app owns the
 * USB device (through the Java USB host API) and lends Hamlib a byte pipe.
 * When Hamlib opens a serial path of the form "android-usb:<id>:<port>",
 * it calls back into the host through the table registered below.
 *
 * Return value contract, for every callback:
 *   >= 0  success (read/write: number of bytes moved; read 0 = timed out)
 *    < 0  failure; RPT_USB_SERIAL_EGONE means the device was detached and
 *         the connection cannot recover.
 * is_ready is the one exception: it returns 1 (ready) or 0 (not ready).
 *
 * parity uses Hamlib's enum serial_parity_e values (0 none, 1 odd, 2 even,
 * 3 mark, 4 space); stop_bits is 1 or 2.
 */
#define RPT_USB_SERIAL_EGONE (-2)

typedef struct rpt_usb_serial_ops
{
    int (*is_ready)(void);
    int (*open)(int device_id, int port_index, int baud_rate, int data_bits,
                int stop_bits, int parity);
    int (*read)(unsigned char *buffer, int length, int timeout_ms);
    int (*write)(const unsigned char *buffer, int length, int timeout_ms);
    int (*set_rts)(int state);
    int (*set_dtr)(int state);
    int (*flush)(void);
    int (*close)(void);
} rpt_usb_serial_ops_t;

/*
 * Register (ops != NULL) or clear (ops == NULL) the host callbacks. The
 * table is copied. Returns RIG_OK, -RIG_EINVAL if a callback is missing, or
 * -RIG_BUSBUSY while a connection is open. Only one android-usb connection
 * can be open at a time.
 */
extern HAMLIB_EXPORT(int) rpt_usb_serial_register(const rpt_usb_serial_ops_t *ops);

#ifdef __cplusplus
}
#endif

#endif /* _RPT_USB_SERIAL_H */
